package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.application.dto.ApplicationResponse;
import com.apliman.cvevaluator.auth.InvalidCredentialsException;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/jobs/{jobId}/applications")
public class ApplicationController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationController.class);

    /** Used when the client sends no Content-Type for the part. */
    private static final String UNKNOWN_CONTENT_TYPE = "application/octet-stream";

    private final StorageService storageService;
    private final ApplicationService applicationService;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;

    public ApplicationController(
            StorageService storageService,
            ApplicationService applicationService,
            JobRepository jobRepository,
            UserRepository userRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.storageService = storageService;
        this.applicationService = applicationService;
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
     * <p>No {@code Location} header yet: 202 should point at a status resource
     * to poll, and {@code GET /api/jobs/{jobId}/applications/{id}} does not
     * exist. The header goes in with that endpoint rather than pointing at a
     * 404 in the meantime.
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
        // From the token, never from a request parameter. Before authentication
        // this came from a header the client set, which meant anyone could
        // submit a CV as anyone. It is now the verified token subject, and the
        // CANDIDATE role gate in SecurityConfig is what stops a recruiter
        // submitting against their own posting.
        Long candidateId = currentUserProvider.currentUserId();

        if (!jobRepository.existsById(jobId)) {
            throw new JobNotFoundException(jobId);
        }
        // A live token for a deleted account. 401, not 500 - the caller can act
        // on "log in again" and cannot act on "something went wrong".
        if (!userRepository.existsById(candidateId)) {
            throw new InvalidCredentialsException();
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

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApplicationResponse(
                saved.getId(),
                jobId,
                saved.getOriginalFilename(),
                saved.getStatus(),
                saved.getSubmittedAt()
        ));
    }
}
