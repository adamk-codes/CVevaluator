package com.apliman.cvevaluator.job;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.application.ApplicationRepository;
import com.apliman.cvevaluator.application.ApplicationStatus;
import com.apliman.cvevaluator.evaluation.EvaluationParseException;
import com.apliman.cvevaluator.evaluation.EvaluationResult;
import com.apliman.cvevaluator.evaluation.EvaluationService;
import com.apliman.cvevaluator.evaluation.LlmEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

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

    public AsyncApplicationReevaluationTrigger(
            ApplicationRepository applications,
            EvaluationService evaluationService,
            LlmEvaluator evaluator
    ) {
        this.applications = applications;
        this.evaluationService = evaluationService;
        this.evaluator = evaluator;
    }

    /**
     * <strong>TODO(D3): move this onto the evaluation {@code TaskExecutor}.</strong>
     * It runs synchronously on the caller's thread today, which means
     * {@code PUT /api/jobs/{id}/requirements} does not return until every
     * application on the job has been through the model. On a job with twenty
     * CVs that is a request held open for minutes — acceptable while the only
     * caller is a demo with three fixtures, not acceptable after D3 builds the
     * executor. The class is named for where it is going, not where it is:
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
            // PENDING and PROCESSING rows are non-FAILED but have no text yet -
            // extraction is still in flight. They are skipped rather than
            // waited for: the evaluation they are owed will be produced when
            // extraction finishes, and blocking here would make this method's
            // duration depend on an unrelated background queue.
            if (!StringUtils.hasText(application.getRedactedText())) {
                log.debug("Application {} has no extracted text yet ({}); skipping re-evaluation",
                        application.getId(), application.getStatus());
                skipped++;
                continue;
            }

            if (reevaluate(job, application)) {
                written++;
            } else {
                failed++;
            }
        }

        log.info("Job {} re-evaluation complete: {} written, {} skipped, {} failed",
                job.getId(), written, skipped, failed);
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
