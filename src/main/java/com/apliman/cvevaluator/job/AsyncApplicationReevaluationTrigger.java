package com.apliman.cvevaluator.job;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.application.ApplicationRepository;
import com.apliman.cvevaluator.application.ApplicationStatus;
import com.apliman.cvevaluator.evaluation.EvaluationParseException;
import com.apliman.cvevaluator.evaluation.EvaluationProperties;
import com.apliman.cvevaluator.evaluation.EvaluationResult;
import com.apliman.cvevaluator.evaluation.EvaluationService;
import com.apliman.cvevaluator.evaluation.LlmEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Re-evaluates every eligible application on a job when its requirements
 * change. Replaces the no-op placeholder that held this seam open.
 *
 * <h2>It only ever inserts</h2>
 *
 * Each application gets a <em>new</em> evaluation row; no existing one is ever
 * modified. The prior evaluation is the evidence that the requirements edit is
 * what moved the candidate, and overwriting it would leave a changed score with
 * nothing to compare against.
 *
 * <p>Older evaluations beyond the retention cap are removed, but that happens
 * in {@link EvaluationService#record}, inside the same transaction as the
 * insert — not here. This class has no delete path of its own.
 *
 * <h2>Not transactional, deliberately</h2>
 *
 * This class has no {@code @Transactional} anywhere and must not acquire one.
 * It makes a network call per application, each taking seconds; holding a
 * database transaction open across that would pin a connection from the pool
 * for the length of the whole burst. Each write is its own short transaction,
 * owned by {@link EvaluationService}. The consequence to know about: a batch
 * that fails halfway leaves
 * the earlier applications evaluated and the rest not. That is the right
 * trade — the rows written are correct and complete, and a partial batch is
 * re-runnable, whereas a rolled-back batch would throw away good evaluations
 * that were already paid for.
 */
@Component
public class AsyncApplicationReevaluationTrigger implements ApplicationReevaluationTrigger {

    private static final Logger log = LoggerFactory.getLogger(AsyncApplicationReevaluationTrigger.class);

    private final ApplicationRepository applications;
    private final EvaluationService evaluationService;
    private final LlmEvaluator evaluator;
    private final EvaluationProperties properties;

    public AsyncApplicationReevaluationTrigger(
            ApplicationRepository applications,
            EvaluationService evaluationService,
            LlmEvaluator evaluator,
            EvaluationProperties properties
    ) {
        this.applications = applications;
        this.evaluationService = evaluationService;
        this.evaluator = evaluator;
        this.properties = properties;
    }

    /**
     * <strong>TODO(D3): move this onto the evaluation {@code TaskExecutor}.</strong>
     * It runs synchronously on the caller's thread today, which means
     * {@code PUT /api/jobs/{id}/requirements} does not return until every
     * application on the job has been through the model — measured at roughly
     * 22 seconds each — plus up to {@code extraction-wait} for any still being
     * extracted. On a job with twenty CVs that is a request held open for
     * minutes: acceptable while the only caller is a demo with three fixtures,
     * not acceptable after D3 builds the executor. The class is named for where
     * it is going, not where it is:
     * everything else about it is already async-shaped, so the change is to
     * submit {@link #reevaluate} per application to the pool and return
     * immediately.
     *
     * <p>The call site in {@code JobController} is already correct for that
     * move — it fires this after {@code save} has committed and outside any
     * transaction of its own, so a worker thread reading the job back will see
     * the new version. See the note on {@code JobController.replaceRequirements}
     * before changing anything there.
     */
    @Override
    public void onRequirementsChanged(Job job) {
        // FAILED means extraction never produced text. Excluded in the query
        // rather than skipped in the loop so the rows are never loaded at all.
        List<Application> eligible =
                applications.findByJobAndStatusNotOrderBySubmittedAt(job, ApplicationStatus.FAILED);

        log.info("Requirements changed for job {} (now version {}): re-evaluating {} application(s)",
                job.getId(), job.getRequirementsVersion(), eligible.size());

        int written = 0;
        int skipped = 0;
        int failed = 0;

        for (Application application : eligible) {
            // A PENDING or PROCESSING row is non-FAILED but has no text yet -
            // its extraction is still queued or running. Waiting rather than
            // skipping is what stops a CV uploaded moments before a
            // requirements edit from silently going unevaluated until the next
            // edit, which for the last CV submitted may be never.
            Optional<Application> extracted = awaitExtraction(application);
            if (extracted.isEmpty()) {
                skipped++;
                continue;
            }

            Application current = extracted.get();

            // Reached a terminal state, but not a usable one: extraction ran and
            // produced nothing, or produced too little. Re-read from the row
            // rather than trusted from the initial query, because the status
            // may have moved to FAILED while this method was waiting.
            if (current.getStatus() == ApplicationStatus.FAILED
                    || !StringUtils.hasText(current.getRedactedText())) {
                log.debug("Application {} has no usable text ({}); skipping re-evaluation",
                        current.getId(), current.getStatus());
                skipped++;
                continue;
            }

            if (reevaluate(job, current)) {
                written++;
            } else {
                failed++;
            }
        }

        log.info("Job {} re-evaluation complete: {} written, {} skipped, {} failed",
                job.getId(), written, skipped, failed);
    }

    /**
     * Blocks until this application's extraction reaches a terminal state.
     *
     * <h2>Why poll instead of waiting on the extraction thread</h2>
     *
     * The two never share a thread or a transaction. Extraction runs on the
     * {@code cv-extract-} pool and commits its own short transactions, so there
     * is no future to join and no lock to wait on — re-reading the row is the
     * only way to observe it finishing. Each read is its own transaction, which
     * is also what makes the new status visible at all: a single long-running
     * transaction here would keep returning the snapshot it started with.
     *
     * <h2>What it costs</h2>
     *
     * This runs on the caller's thread, which today is the thread serving
     * {@code PUT /api/jobs/{id}/requirements}. That request already blocks for
     * one model call per application; this adds up to
     * {@code extraction-wait} more per application that is still extracting.
     * The bound is what keeps it survivable — without a deadline, one
     * application stuck in PROCESSING would hold the request open forever. Set
     * {@code cvevaluator.evaluation.extraction-wait=0} to restore the old
     * skip-immediately behaviour.
     *
     * @return the freshly-read application once its extraction is terminal, or
     *         empty if the wait expired, the thread was interrupted, or the row
     *         disappeared
     */
    private Optional<Application> awaitExtraction(Application application) {
        if (isTerminal(application.getStatus())) {
            return Optional.of(application);
        }

        long deadline = System.nanoTime() + properties.extractionWait().toNanos();
        long pollMillis = properties.extractionPollInterval().toMillis();

        log.info("Application {} is {}; waiting up to {} for extraction to finish",
                application.getId(), application.getStatus(), properties.extractionWait());

        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                // Restore the flag and give up rather than swallowing it. A
                // cleared interrupt here would leave a shutting-down executor
                // unable to stop this loop.
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for application {} to finish extracting",
                        application.getId());
                return Optional.empty();
            }

            Optional<Application> current = applications.findById(application.getId());
            if (current.isEmpty()) {
                log.warn("Application {} disappeared while waiting for extraction", application.getId());
                return Optional.empty();
            }
            if (isTerminal(current.get().getStatus())) {
                return current;
            }
        }

        log.warn("Application {} did not finish extracting within {}; skipping it. It will be "
                        + "evaluated on the next requirements change.",
                application.getId(), properties.extractionWait());
        return Optional.empty();
    }

    /**
     * COMPLETED and FAILED are the two states extraction stops at. Everything
     * else means it has not run yet or is running now.
     */
    private static boolean isTerminal(ApplicationStatus status) {
        return status == ApplicationStatus.COMPLETED || status == ApplicationStatus.FAILED;
    }

    /**
     * One application, one new row.
     *
     * @return whether an evaluation was written
     */
    private boolean reevaluate(Job job, Application application) {
        try {
            // redactedText, not extractedText. PII must not leave the process,
            // and the D3 grounding checker has to match quotes against the same
            // text the model saw - see the parameter note on LlmEvaluator.evaluate.
            EvaluationResult result = evaluator.evaluate(job, application.getRedactedText());

            // The model call above is finished before this line, so the write
            // transaction inside record() never spans a network round trip.
            // Moving the evaluate() call inside a transactional method would
            // undo that quietly - see the class note.
            evaluationService.record(application, result);
            return true;

        } catch (EvaluationParseException e) {
            // Caught by its own type, separately from the catch below, because
            // this one is not a bug: it means the model returned something that
            // broke the output contract, which is a known and expected failure
            // mode. The application keeps its previous evaluation and its
            // status - a bad re-evaluation must not destroy a good one, and
            // FAILED on an Application means "the CV could not be read", which
            // is not what happened here.
            log.warn("Application {}: model response rejected, keeping the previous evaluation. {}",
                    application.getId(), e.getMessage());
            return false;

        } catch (RuntimeException e) {
            // Everything else: provider timeouts, rate limits, a bug here. One
            // application failing must not abandon the rest of the batch, which
            // is why this is caught per application rather than around the loop.
            log.error("Application {}: re-evaluation failed", application.getId(), e);
            return false;
        }
    }
}
