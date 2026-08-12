package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The transaction boundary for stored evaluations, and the one place the
 * history cap is applied.
 *
 * <p>It exists because writing an evaluation stopped being a single
 * {@code save}. A new row and the removal of whatever fell off the end of the
 * history are one change to the record — if the insert commits and the prune
 * does not, the cap is silently exceeded and nothing ever revisits it. Both
 * happen in one transaction here, which is exactly what a bare repository call
 * from the trigger could not give.
 *
 * <p>Nothing in this class talks to a model or touches a file, so being
 * {@code @Transactional} is safe. The LLM call happens in {@code LlmEvaluator},
 * before any of this is entered, and the caller is responsible for keeping it
 * that way — see {@code AsyncApplicationReevaluationTrigger}.
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final EvaluationRepository evaluations;
    private final EvaluationProperties properties;

    public EvaluationService(EvaluationRepository evaluations, EvaluationProperties properties) {
        this.evaluations = evaluations;
        this.properties = properties;
    }

    /**
     * Stores a completed evaluation and trims the history back to the cap.
     *
     * <h2>What changed about append-only</h2>
     *
     * This used to be a promise that no evaluation is ever deleted. It is now a
     * bounded window: the newest {@code cvevaluator.evaluation.max-per-application}
     * survive and older ones are removed. The reason for the original rule still
     * holds — a prior evaluation is the evidence that a requirements edit is what
     * moved a candidate — but that argument only ever needed <em>recent</em>
     * history, and an unbounded one grows by two {@code jsonb} blobs per
     * application on every requirements edit, indefinitely, for rows nobody
     * reads.
     *
     * <p>What has not changed: an existing evaluation is never <em>modified</em>.
     * {@link Evaluation} still has no setters. A row is either present exactly as
     * it was written, or gone.
     */
    @Transactional
    public Evaluation record(Application application, EvaluationResult result) {
        Evaluation saved = evaluations.save(new Evaluation(application, result));
        prune(application);
        return saved;
    }

    /**
     * The current evaluation for an application, if it has one.
     *
     * <p>Empty is a normal answer — an application that has been extracted but
     * never evaluated has no rows yet.
     */
    @Transactional(readOnly = true)
    public Optional<Evaluation> findLatest(Application application) {
        return evaluations.findFirstByApplicationOrderByCreatedAtDesc(application);
    }

    /** The whole retained history, newest first. At most the configured cap. */
    @Transactional(readOnly = true)
    public List<Evaluation> findHistory(Application application) {
        return evaluations.findByApplicationOrderByCreatedAtDesc(application);
    }

    /**
     * Drops everything past the newest {@code maxPerApplication}.
     *
     * <p>Loads the history to find what to delete rather than issuing a bulk
     * {@code DELETE} with a subquery. At a cap of five the read is six rows at
     * most, and a derived query keeps the ordering rule in one place — a
     * hand-written {@code DELETE ... NOT IN (SELECT ... LIMIT n)} would restate
     * "newest first" in SQL where it could drift from the annotation on the
     * entity and from the repository method.
     *
     * <p>Trimming after the insert rather than before means the write is never
     * blocked by a failure to clean up, and the cap is briefly exceeded inside
     * this transaction only.
     */
    private void prune(Application application) {
        List<Evaluation> history = evaluations.findByApplicationOrderByCreatedAtDesc(application);
        int cap = properties.maxPerApplication();

        if (history.size() <= cap) {
            return;
        }

        List<Evaluation> excess = List.copyOf(history.subList(cap, history.size()));
        evaluations.deleteAll(excess);

        log.info("Application {}: evaluation history trimmed to {}; removed {} older evaluation(s)",
                application.getId(), cap, excess.size());
    }
}
