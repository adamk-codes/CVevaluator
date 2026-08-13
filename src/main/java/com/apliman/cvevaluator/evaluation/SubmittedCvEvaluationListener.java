package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.application.ApplicationRepository;
import com.apliman.cvevaluator.application.ApplicationStatus;
import com.apliman.cvevaluator.application.CvExtractedEvent;
import com.apliman.cvevaluator.job.Job;
import com.apliman.cvevaluator.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

/**
 * Evaluates a CV as soon as it has usable text.
 *
 * <h2>What this closes</h2>
 *
 * Submitting a CV used to end at extraction: the row reached COMPLETED and
 * nothing further happened. An evaluation appeared only when a recruiter next
 * edited the job's requirements, so "upload a CV, see a verdict" was not a
 * path that existed — and for the last CV submitted before a recruiter stopped
 * editing, the evaluation might never have happened at all.
 *
 * <h2>Why it is a listener and not a call from the ingestion service</h2>
 *
 * {@code CvIngestionService} would have to depend on the evaluation package to
 * call this directly, which points the dependency the wrong way: extraction has
 * no interest in what happens to the text afterwards. An event keeps extraction
 * unaware of evaluation and lets a second consumer be added later without
 * touching it.
 */
@Component
public class SubmittedCvEvaluationListener {

    private static final Logger log = LoggerFactory.getLogger(SubmittedCvEvaluationListener.class);

    private final ApplicationRepository applications;
    private final JobRepository jobs;
    private final LlmEvaluator evaluator;
    private final EvaluationService evaluationService;

    public SubmittedCvEvaluationListener(
            ApplicationRepository applications,
            JobRepository jobs,
            LlmEvaluator evaluator,
            EvaluationService evaluationService
    ) {
        this.applications = applications;
        this.jobs = jobs;
        this.evaluator = evaluator;
        this.evaluationService = evaluationService;
    }

    /**
     * Both annotations are load-bearing and answer different questions.
     *
     * <p>{@code AFTER_COMMIT} answers <em>when</em>. The event is published
     * while the transaction that wrote the extracted text is still open. A
     * plain {@code @EventListener} would fire inside it, and this method would
     * read the row on a different connection that cannot see the text yet — so
     * it would evaluate an empty CV. That failure is a race, which means it
     * would pass locally and appear under load.
     *
     * <p>{@code @Async} answers <em>where</em>. Without it the evaluation runs
     * on the extraction thread, and one twenty-second model call per CV would
     * throttle the extraction pool to the speed of the model. With it, the two
     * stages have separate pools and extraction stays free to keep up with
     * uploads.
     */
    @Async(EvaluationExecutorConfig.EVALUATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCvExtracted(CvExtractedEvent event) {
        evaluate(event.applicationId());
    }

    /**
     * Public so a stuck application can be re-driven by hand, and so this is
     * testable without an event or a transaction.
     */
    public void evaluate(Long applicationId) {
        Application application = applications.findById(applicationId).orElse(null);
        if (application == null) {
            log.warn("Application {} no longer exists; skipping evaluation", applicationId);
            return;
        }

        // Re-read state rather than trusting the event. The row could have moved
        // to FAILED, or been re-extracted, between publish and this thread
        // starting.
        if (application.getStatus() != ApplicationStatus.COMPLETED
                || !StringUtils.hasText(application.getRedactedText())) {
            log.debug("Application {} is {} with no usable text; skipping evaluation",
                    applicationId, application.getStatus());
            return;
        }

        // Loaded by id rather than taken from application.getJob(), and this is
        // load-bearing. The repository call above has already committed, so the
        // Application is detached and its job is an uninitialised proxy -
        // LlmEvaluator reads job.getRequirements(), which on that proxy throws
        // LazyInitializationException and fails every evaluation on submission.
        //
        // Calling getId() on the proxy is safe: the identifier is known without
        // initialising it, which is why this line can ask for it at all. The
        // Job that comes back is a fully loaded detached entity, and
        // requirements is a jsonb basic attribute rather than an association,
        // so it is populated by this read with no further session needed.
        //
        // The alternative - annotating this method @Transactional so the proxy
        // stays attached - would hold a database connection open across a model
        // call that takes tens of seconds. Both this class and
        // AsyncApplicationReevaluationTrigger deliberately avoid that.
        Job job = jobs.findById(application.getJob().getId()).orElse(null);
        if (job == null) {
            log.warn("Job for application {} no longer exists; skipping evaluation", applicationId);
            return;
        }

        try {
            // Redacted text, never extractedText: PII must not leave the
            // process, and it is also what GroundingChecker matches quotes
            // against. See LlmEvaluator.evaluate.
            EvaluationResult result =
                    evaluator.evaluate(job, application.getRedactedText());

            // The model call is finished before this line, so the write
            // transaction inside record() never spans a network round trip.
            evaluationService.record(application, result);

            log.info("Application {} evaluated on submission: {}", applicationId, result.verdict());

        } catch (EvaluationParseException e) {
            // The model broke the output contract - a skipped requirement, a
            // quote that is not in the CV. Not a bug here, and not worth a
            // stack trace: the application keeps no evaluation and will get one
            // on the next requirements change.
            log.warn("Application {}: evaluation rejected - {}", applicationId, e.getMessage());

        } catch (RuntimeException e) {
            // Provider timeouts, rate limits, anything else. Logged with the
            // stack because unlike the above these are not expected.
            log.error("Application {}: evaluation failed", applicationId, e);
        }
    }
}
