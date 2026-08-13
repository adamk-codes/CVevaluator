package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.application.dto.ApplicationStatusResponse;
import com.apliman.cvevaluator.job.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByJobOrderBySubmittedAt(Job job);

    /**
     * The recruiter's list of CVs on one job, as DTOs rather than entities.
     *
     * <p>A constructor expression, not {@code findByJob...} plus a mapping step,
     * and the difference is the whole reason this exists. {@code Application}
     * has two {@code TEXT} columns — {@code extractedText} and
     * {@code redactedText} — which are eager by default because they are basic
     * fields. Loading entities to build this list would pull every CV body on
     * the job into memory to display a filename and a status. With twenty CVs
     * of a few kilobytes each that is invisible; it is also exactly the kind of
     * thing that is invisible until it is not. Watch the SQL with
     * {@code show-sql=true}: this selects nine named columns and no text.
     *
     * <p>{@code a.job.id} reads the foreign key already on the row. It does not
     * join {@code jobs} — writing {@code a.job.id} rather than joining is what
     * keeps this a single-table select.
     *
     * <p>Takes a {@code jobId} rather than a {@link Job} so the caller does not
     * have to load a job it only needs the id of. The caller checks the job
     * exists separately, because an unknown id and a job with no applications
     * both return an empty list here and they are different answers.
     */
    @Query("""
            select new com.apliman.cvevaluator.application.dto.ApplicationStatusResponse(
                a.id, a.job.id, a.originalFilename, a.status, a.failureReason,
                a.sizeBytes, a.textLength, a.extractionMethod, a.submittedAt)
            from Application a
            where a.job.id = :jobId
            order by a.submittedAt
            """)
    List<ApplicationStatusResponse> findSummariesByJobId(@Param("jobId") Long jobId);

    /**
     * One CV's status, by its own id and the job it must belong to.
     *
     * <p>Scoped by {@code jobId} for the same reason
     * {@code EvaluationService.delete} checks ownership: without it, a guessed
     * application id under any job path would return another candidate's
     * submission. Here the wrong pairing is simply absent, which the controller
     * turns into a 404.
     */
    @Query("""
            select new com.apliman.cvevaluator.application.dto.ApplicationStatusResponse(
                a.id, a.job.id, a.originalFilename, a.status, a.failureReason,
                a.sizeBytes, a.textLength, a.extractionMethod, a.submittedAt)
            from Application a
            where a.id = :applicationId and a.job.id = :jobId
            """)
    Optional<ApplicationStatusResponse> findSummaryByIdAndJobId(
            @Param("applicationId") Long applicationId,
            @Param("jobId") Long jobId);

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
