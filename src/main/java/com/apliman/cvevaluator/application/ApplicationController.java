package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.application.dto.ApplicationResponse;
import com.apliman.cvevaluator.application.dto.ApplicationStatusResponse;
import com.apliman.cvevaluator.job.JobNotFoundException;
import com.apliman.cvevaluator.job.JobRepository;
import com.apliman.cvevaluator.security.CurrentUserProvider;
import com.apliman.cvevaluator.storage.StorageService;
import com.apliman.cvevaluator.storage.StoredFile;
import com.apliman.cvevaluator.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/jobs/{jobId}/applications")
public class ApplicationController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationController.class);

    /** Used when the client sends no Content-Type for the part. */
    private static final String UNKNOWN_CONTENT_TYPE = "application/octet-stream";

    private final StorageService storageService;
    private final ApplicationService applicationService;
    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;

    public ApplicationController(
            StorageService storageService,
            ApplicationService applicationService,
            ApplicationRepository applicationRepository,
            JobRepository jobRepository,
            UserRepository userRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.storageService = storageService;
        this.applicationService = applicationService;
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Submits a CV against a job. Returns 202 immediately - the row is created
     * as PENDING and text extraction happens later, off the request thread.
     *
     * <p>This method is deliberately <em>not</em> {@code @Transactional}. It
     * orchestrates two steps that must not share a transaction: the file write
     * (slow, external, not rollback-able) and the row insert. Only the second
     * is transactional, inside {@link ApplicationService#create}.
     *
     * <p>Carries a {@code Location} header pointing at {@link #status}, which
     * is the resource a client polls until the status is terminal. The header
     * was held back until that endpoint existed rather than pointing at a 404.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApplicationResponse> submitCv(
            @PathVariable Long jobId,
            @RequestParam("file") MultipartFile file
    ) {
        // Ordering below is load-bearing, and it is the whole point of this method.
        //
        // 1. Cheap checks that can reject the request outright run FIRST, before
        //    anything is written. If the existence checks lived inside the
        //    transactional insert instead, every request naming a job that does
        //    not exist would leave a file on disk with no row referencing it.
        // 2. Then the file write, outside any transaction.
        // 3. Then the insert, which is the only transactional step.
        Long candidateId = currentUserProvider.currentUserId();

        if (!jobRepository.existsById(jobId)) {
            throw new JobNotFoundException(jobId);
        }
        if (!userRepository.existsById(candidateId)) {
            throw new IllegalStateException("Current user not found");
        }

        // Disk first, database second. Reversing this trades a harmless orphan
        // file for a row whose storagePath points at nothing, which every later
        // stage of the pipeline would have to defend against.
        StoredFile stored = storageService.store(file);

        // The client's declared Content-Type. Nullable when the part carries no
        // type header, and the column is NOT NULL - Hibernate would throw on
        // insert. Treated as a display value only: the extension allowlist in
        // FileSystemStorageService is the actual gate, since this string is
        // whatever the client felt like sending.
        String contentType = StringUtils.hasText(file.getContentType())
                ? file.getContentType()
                : UNKNOWN_CONTENT_TYPE;

        Application saved;
        try {
            saved = applicationService.create(jobId, candidateId, stored, contentType);
        } catch (RuntimeException e) {
            // The file is already on disk and the row did not land, so it is now
            // unreferenced. Logged rather than deleted: cleanup would mean adding
            // a delete operation to StorageService, and an orphaned file is inert
            // where a dangling storagePath is not.
            log.warn("Insert failed after storing file; orphaned storage key: {}", stored.storageKey());
            throw e;
        }

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/jobs/" + jobId + "/applications/" + saved.getId()))
                .body(new ApplicationResponse(
                        saved.getId(),
                        jobId,
                        saved.getOriginalFilename(),
                        saved.getStatus(),
                        saved.getSubmittedAt()
                ));
    }

    /**
     * Every CV submitted against one job, oldest first.
     *
     * <p>The recruiter's list. Submission order rather than newest-first so it
     * reads in the same order as the re-evaluation queue in
     * {@code ApplicationRepository.findByJobAndStatusNotOrderBySubmittedAt} —
     * two screens disagreeing about the order of the same rows is a bug report
     * waiting to happen.
     *
     * <p>The job existence check is not redundant with the query returning an
     * empty list. A job with no CVs and a job id that does not exist are
     * different answers and the client acts differently on each: one is an
     * empty state, the other is a broken link.
     *
     * <p>Returns DTOs straight from the repository — see
     * {@link ApplicationRepository#findSummariesByJobId} for why this does not
     * load entities and map them.
     */
    @GetMapping
    public List<ApplicationStatusResponse> list(@PathVariable Long jobId) {
        if (!jobRepository.existsById(jobId)) {
            throw new JobNotFoundException(jobId);
        }
        return applicationRepository.findSummariesByJobId(jobId);
    }

    /**
     * One CV's position in the pipeline — the poll target named by the 202's
     * {@code Location} header.
     *
     * <p>Terminal states are {@code COMPLETED} and {@code FAILED}; a client
     * stops polling on either. Note that {@code COMPLETED} means the text
     * extracted, <strong>not</strong> that an evaluation exists — evaluation is
     * a second async stage fired from {@code CvExtractedEvent}, so a client
     * that wants a verdict polls {@code GET
     * /api/applications/{id}/evaluation} after this reaches COMPLETED and
     * treats its 404 as "not yet". Collapsing the two stages into one status
     * enum was the alternative; it was rejected because a CV that extracts
     * fine and then trips the grounding checker would be indistinguishable
     * from one that never parsed.
     *
     * <p>404 when the application exists but belongs to a different job, not
     * just when the id is unknown. The path asserts a relationship and a path
     * that asserts something false is not found.
     */
    @GetMapping("/{applicationId}")
    public ApplicationStatusResponse status(
            @PathVariable Long jobId,
            @PathVariable Long applicationId
    ) {
        if (!jobRepository.existsById(jobId)) {
            throw new JobNotFoundException(jobId);
        }
        return applicationRepository.findSummaryByIdAndJobId(applicationId, jobId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }
}
