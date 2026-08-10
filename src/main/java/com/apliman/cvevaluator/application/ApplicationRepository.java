package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.job.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByJobOrderBySubmittedAt(Job job);

    /**
     * How many CVs are attached to a job. Derived query, so it is one
     * {@code SELECT COUNT} — the reason it exists rather than callers taking
     * {@code findByJobOrderBySubmittedAt(job).size()} and loading every row and
     * its extracted text to count them.
     */
    long countByJob(Job job);

    /**
     * Every application on a job except the ones whose CV never extracted.
     *
     * <p>The exclusion is the point: a FAILED row has no text, so re-evaluating
     * it would produce an assessment grounded in nothing — exactly what the
     * grounding checker exists to catch, arrived at deliberately.
     *
     * <p>Submission order, so a re-evaluation burst processes oldest first and
     * the log reads in the same order as the applications list a recruiter is
     * looking at.
     */
    List<Application> findByJobAndStatusNotOrderBySubmittedAt(Job job, ApplicationStatus status);
}
