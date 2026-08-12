package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Queries over stored evaluations.
 *
 * <p>Two read methods, and the split is the point: almost every caller wants
 * <em>the</em> evaluation, not the history. Serving that from
 * {@link #findByApplicationOrderByCreatedAtDesc} means loading every row for
 * the application — each carrying two {@code jsonb} blobs of assessments and a
 * summary — in order to look at the first one and discard the rest. The history
 * is a deliberate ask, not the default.
 *
 * <p>Prefer {@link EvaluationService} over calling this directly for writes.
 * {@code save} here inserts a row and nothing else; the retention cap is
 * applied in the service, so a direct save leaves the history unbounded.
 */
public interface EvaluationRepository extends JpaRepository<Evaluation, Long> {

    /**
     * The current evaluation, or empty if this application has never been
     * evaluated.
     *
     * <p>{@code findFirst} becomes {@code LIMIT 1} in the generated SQL, so this
     * reads exactly one row no matter how long the history is.
     *
     * <p>Empty is a normal answer, not an error: an application sits at
     * {@code COMPLETED} with no evaluation until a requirements change triggers
     * one, which is the usual state right after a CV is submitted.
     */
    Optional<Evaluation> findFirstByApplicationOrderByCreatedAtDesc(Application application);

    /**
     * The whole history, newest first, for showing how a candidate moved across
     * requirement edits.
     *
     * <p>Ordered by {@code createdAt} rather than by {@code id} because the
     * ordering that matters is chronological; tying it to a sequence would make
     * it accidental.
     */
    List<Evaluation> findByApplicationOrderByCreatedAtDesc(Application application);
}
