package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.job.JobRepository;
import com.apliman.cvevaluator.storage.StoredFile;
import com.apliman.cvevaluator.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The transaction boundary for creating an {@link Application}.
 *
 * <p>Per project rule, file I/O is never inside a transaction. That rule is
 * enforced by this class's signature rather than by a comment: {@link #create}
 * takes an already-written {@link StoredFile}, not a {@code MultipartFile}, so
 * there is nothing here to write to disk even by accident. The caller does the
 * write first, outside any transaction, and hands the result in.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    /**
     * {@code failureReason} is a plain {@code String} field, so {@code ddl-auto}
     * created it as {@code varchar(255)}. A longer message would fail at flush,
     * on a background thread, leaving the row stuck in PROCESSING — the exact
     * state the failure path exists to avoid. Truncating is the cheaper answer
     * than widening the column and hoping.
     */
    private static final int MAX_FAILURE_REASON_LENGTH = 255;

    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;

    public ApplicationService(
            ApplicationRepository applicationRepository,
            JobRepository jobRepository,
            UserRepository userRepository,
            ApplicationEventPublisher events
    ) {
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
        this.events = events;
    }

    /**
     * Inserts the row for a CV that is already on disk.
     *
     * <p>Both associations are resolved with {@code getReferenceById}, not
     * {@code findById}. Two reasons:
     *
     * <ul>
     *   <li>The insert only needs the foreign keys, so loading the full rows
     *       would be two SELECTs for columns nobody reads. With
     *       {@code show-sql=true} you should see the caller's existence checks
     *       and then the INSERT - and no SELECT from this method.
     *   <li>The caller checks existence <em>outside</em> this transaction. If it
     *       passed the loaded entities in, they would arrive detached and have
     *       to be merged back into this persistence context. Passing ids and
     *       taking proxies here avoids that entirely.
     * </ul>
     *
     * <p>The cost is that a nonexistent id is not caught here - it surfaces as a
     * foreign key violation at flush rather than a clean 404. The caller checks
     * first precisely so that path is unreachable in practice.
     */
    @Transactional
    public Application create(Long jobId, Long candidateId, StoredFile stored, String contentType) {
        Application application = new Application(
                jobRepository.getReferenceById(jobId),
                userRepository.getReferenceById(candidateId),
                stored.originalFilename(),
                contentType,
                stored.sizeBytes(),
                stored.storageKey()
        );
        Application saved = applicationRepository.save(application);

        // Published inside the transaction, consumed after it commits - see
        // ApplicationCreatedEvent and CvIngestionService. Publishing here rather
        // than in the controller keeps the guarantee honest: there is no path
        // that writes the row without announcing it.
        events.publishEvent(new ApplicationCreatedEvent(saved.getId(), saved.getStoragePath()));
        return saved;
    }

    /**
     * Moves the row to PROCESSING as extraction begins.
     *
     * <p>Its own short transaction, not folded into the one that finishes the
     * job. A polling client is meant to see PENDING, then PROCESSING, then a
     * terminal state; writing PROCESSING in the same transaction that writes
     * COMPLETED would make it a value no client ever observes.
     */
    @Transactional
    public void markProcessing(Long applicationId) {
        find(applicationId).ifPresent(application -> application.setStatus(ApplicationStatus.PROCESSING));
    }

    /**
     * Records a successful extraction and moves the row to COMPLETED.
     *
     * <p>No explicit {@code save}: these entities are managed inside this
     * transaction, so Hibernate's dirty checking issues the UPDATE at flush.
     */
    @Transactional
    public void recordExtraction(Long applicationId, String extractedText, String redactedText, String method) {
        find(applicationId).ifPresent(application -> {
            application.setExtractedText(extractedText);
            application.setRedactedText(redactedText);
            application.setExtractionMethod(method);
            application.setTextLength(extractedText.length());
            application.setFailureReason(null);
            application.setStatus(ApplicationStatus.COMPLETED);
        });
    }

    /**
     * Records a readable reason and moves the row to FAILED.
     *
     * <p>{@code reason} is shown to the candidate, so callers must pass a
     * message they authored — never a raw exception message from Hibernate,
     * PDFBox or the filesystem, which carry paths and internals.
     */
    @Transactional
    public void recordExtractionFailure(Long applicationId, String reason) {
        find(applicationId).ifPresent(application -> {
            application.setFailureReason(truncate(reason));
            application.setStatus(ApplicationStatus.FAILED);
        });
    }

    /**
     * A missing row is logged and shrugged off rather than thrown, because every
     * caller of the three methods above runs on a background thread where
     * nobody is waiting to catch anything. The only way to get here is an
     * application deleted between submission and extraction, and the right
     * response to that is to stop, not to fill a log with stack traces.
     */
    private Optional<Application> find(Long applicationId) {
        Optional<Application> application = applicationRepository.findById(applicationId);
        if (application.isEmpty()) {
            log.warn("Application {} no longer exists; skipping status update", applicationId);
        }
        return application;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= MAX_FAILURE_REASON_LENGTH) {
            return reason;
        }
        log.warn("Truncating an over-long failure reason: {}", reason);
        return reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }
}
