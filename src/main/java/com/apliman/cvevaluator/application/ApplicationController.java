package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.application.dto.ApplicationResponse;
import com.apliman.cvevaluator.application.dto.ApplicationStatusResponse;
import com.apliman.cvevaluator.auth.InvalidCredentialsException;
import com.apliman.cvevaluator.job.JobNotFoundException;
import com.apliman.cvevaluator.job.JobRepository;
import com.apliman.cvevaluator.security.CurrentUserProvider;
import com.apliman.cvevaluator.storage.StorageService;
import com.apliman.cvevaluator.storage.StoredFile;
import com.apliman.cvevaluator.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

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
        // Scoped to the caller's own postings, not merely to an existing job.
        // The role gate in SecurityConfig establishes that this is a recruiter;
        // it says nothing about whether it is *their* posting, and without this
        // any recruiter could read the applicant list of every job on the
        // platform. 404 rather than 403 for the same reason as
        // JobRepository.findByIdAndCreatedByRecruiter_Id - a 403 confirms the
        // job exists and belongs to someone else.
        jobRepository.findByIdAndCreatedByRecruiter_Id(jobId, currentUserProvider.currentUserId())
                .orElseThrow(() -> new JobNotFoundException(jobId));

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
     *
     * <p>Reachable by both roles, which is why the authorization is ownership
     * rather than a role gate: the candidate who submitted it, or the recruiter
     * who posted the job, and nobody else. That is enforced in the query — see
     * {@link ApplicationRepository#findSummaryVisibleTo}. A candidate polling a
     * stranger's application id gets the same 404 as one polling an id that was
     * never issued.
     */
    @GetMapping("/{applicationId}")
    public ApplicationStatusResponse status(
            @PathVariable Long jobId,
            @PathVariable Long applicationId
    ) {
        return applicationRepository
                .findSummaryVisibleTo(applicationId, jobId, currentUserProvider.currentUserId())
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }

    /**
     * The CV itself, as the candidate uploaded it.
     *
     * <p>The recruiter's read of the actual document. Everything else in this
     * API describes the CV — a status, a verdict, a quote — and none of it
     * substitutes for reading it, least of all when a recruiter wants to
     * disagree with an assessment.
     *
     * <p>Serves the <em>original</em> file, so it carries whatever contact
     * details the candidate wrote. That is not a redaction failure: redaction
     * exists to keep PII away from the model, and this file never goes near
     * one. A recruiter who cannot see a phone number cannot make a phone call.
     *
     * <h2>Serving somebody else's uploaded bytes</h2>
     *
     * Three things here are security decisions rather than defaults:
     *
     * <ul>
     *   <li><strong>Content type is derived from the extension</strong>, which
     *       {@code FileSystemStorageService} has already checked against the
     *       file's magic bytes. The stored {@code contentType} column is not
     *       used: it is whatever the uploading client typed, and echoing it
     *       back lets an uploader choose the type their file is served as.
     *   <li><strong>{@code nosniff}</strong>, so a browser cannot decide for
     *       itself that a .txt looks like HTML and render it as a page on this
     *       origin.
     *   <li><strong>{@code inline} only for PDF</strong>, which browsers render
     *       in a sandboxed viewer. DOCX and TXT download instead. A .txt shown
     *       inline is served as text/plain and is not executed, but attachment
     *       is the safer default for anything this application does not
     *       positively want rendered.
     * </ul>
     *
     * <p>The filename in the header is the candidate's, quoted and stripped of
     * quotes and control characters — an unescaped one would let a chosen
     * filename inject header fields.
     */
    @GetMapping("/{applicationId}/file")
    public ResponseEntity<Resource> file(
            @PathVariable Long jobId,
            @PathVariable Long applicationId
    ) {
        CvFileLocation location = applicationRepository
                .findFileVisibleTo(applicationId, jobId, currentUserProvider.currentUserId())
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        // load() applies the traversal check. A row written by an older build,
        // or a file removed from disk underneath us, surfaces as StorageException
        // and becomes a 500 with a fixed message - see GlobalExceptionHandler.
        Path path = storageService.load(location.storageKey());

        MediaType contentType = contentTypeOf(location.originalFilename());
        boolean renderInline = MediaType.APPLICATION_PDF.equals(contentType);

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition
                        .builder(renderInline ? "inline" : "attachment")
                        .filename(safeFilename(location.originalFilename()))
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(path));
    }

    /**
     * From the extension, which the signature validator has already matched
     * against the file's leading bytes. Anything else is served as bytes with
     * no claimed meaning, which is the right answer for a file whose extension
     * the allowlist should have refused.
     */
    private static MediaType contentTypeOf(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (lower.endsWith(".docx")) {
            return MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        if (lower.endsWith(".txt")) {
            return new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /** Strips what would otherwise let a filename break out of the header. */
    private static String safeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "cv";
        }
        return filename.replaceAll("[\\p{Cntrl}\"\\\\]", "").trim();
    }
}
