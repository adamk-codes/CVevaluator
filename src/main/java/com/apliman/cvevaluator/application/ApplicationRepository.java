package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.job.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByJobOrderBySubmittedAt(Job job);

    /**
     * This application, but only if this recruiter posted the job it was
     * submitted against.
     *
     * <p>Two hops — {@code application → job → createdByRecruiter} — and both
     * are LAZY, which is exactly why this is a query and not a check written
     * out in the controller. Reading {@code application.getJob()
     * .getCreatedByRecruiter().getId()} on the detached instance that
     * {@code findById} hands back throws {@code LazyInitializationException};
     * the derived query resolves the whole path in one join instead.
     *
     * <p>Empty covers both "no such application" and "not yours", deliberately.
     * The evaluation endpoints turn it into the same 404 either way, so a
     * recruiter cannot walk the id space discovering which applications exist
     * on other people's postings.
     */
    Optional<Application> findByIdAndJob_CreatedByRecruiter_Id(Long id, Long recruiterId);

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
