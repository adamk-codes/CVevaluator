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
     * One CV's status, visible to the candidate who submitted it and to the
     * recruiter who posted the job — and to nobody else.
     *
     * <p>This is the one endpoint both roles reach, so authorization is
     * expressed as ownership rather than as a role, and the {@code or} is that
     * rule written once. Doing it in the query rather than in the handler is
     * not a style preference: {@code a.candidate} and
     * {@code a.job.createdByRecruiter} are both LAZY, so a handler that loaded
     * the row and then compared ids would throw
     * {@code LazyInitializationException} on the detached instance — the same
     * trap {@link #findByIdAndJob_CreatedByRecruiter_Id} exists to avoid.
     *
     * <p>{@code jobId} stays in the predicate even though {@code applicationId}
     * is unique. The path asserts the application belongs to that job, and a
     * path asserting something false should not resolve.
     *
     * <p>Empty covers "no such application", "wrong job" and "not yours"
     * alike, and the controller turns all three into the same 404. A 403 on the
     * last case would confirm the row exists, which is exactly what an id-space
     * walk is looking for.
     */
    @Query("""
            select new com.apliman.cvevaluator.application.dto.ApplicationStatusResponse(
                a.id, a.job.id, a.originalFilename, a.status, a.failureReason,
                a.sizeBytes, a.textLength, a.extractionMethod, a.submittedAt)
            from Application a
            where a.id = :applicationId
              and a.job.id = :jobId
              and (a.candidate.id = :userId or a.job.createdByRecruiter.id = :userId)
            """)
    Optional<ApplicationStatusResponse> findSummaryVisibleTo(
            @Param("applicationId") Long applicationId,
            @Param("jobId") Long jobId,
            @Param("userId") Long userId);

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
