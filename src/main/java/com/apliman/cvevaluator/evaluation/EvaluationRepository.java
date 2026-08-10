package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Reads and inserts only.
 *
 * <p>{@code JpaRepository} does hand out {@code delete} and a {@code save} that
 * will update a managed instance — the append-only rule in {@link Evaluation}
 * is a discipline here, not something the type prevents. It is enforced by
 * {@code Evaluation} having no setters, so there is nothing for a dirty check
 * to notice.
 */
public interface EvaluationRepository extends JpaRepository<Evaluation, Long> {

    /**
     * Newest first, so the head of the list is the current evaluation and the
     * tail is the history. Ordering by {@code createdAt} rather than by {@code id}
     * because the ordering that matters is chronological, and tying it to a
     * sequence would make it accidental.
     */
    List<Evaluation> findByApplicationOrderByCreatedAtDesc(Application application);
}
