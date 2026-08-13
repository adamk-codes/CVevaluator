package com.apliman.cvevaluator.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job,Long> {

    /**
     * This job, but only if this recruiter created it.
     *
     * <p>The ownership check expressed as the query rather than as an
     * {@code if} after {@code findById}. Two reasons, and the second is the
     * important one:
     *
     * <ol>
     *   <li>{@code createdByRecruiter} is LAZY, so the {@code if} version would
     *       have to touch the association outside a transaction and take a
     *       {@code LazyInitializationException} for it.</li>
     *   <li>An empty result means "no such job" and "not your job" identically,
     *       so the 404 that follows is the same either way. A separate 403 for
     *       the second case would confirm to any recruiter that job 57 exists
     *       and belongs to someone else — enough to enumerate the whole table
     *       one id at a time.</li>
     * </ol>
     *
     * <p>The underscore in {@code CreatedByRecruiter_Id} is not decoration: it
     * tells Spring Data where the property boundary is, rather than leaving it
     * to guess whether {@code createdByRecruiterId} is a field on {@code Job}.
     */
    Optional<Job> findByIdAndCreatedByRecruiter_Id(Long id, Long recruiterId);

    /**
     * The postings one recruiter owns, newest first.
     *
     * <p>What a recruiter's dashboard lists. {@code findAll} was what it used
     * before and it returned every posting on the platform — including ones
     * this recruiter cannot open the applicants of, since that read is
     * ownership-scoped. The two disagreeing is what produced a dashboard whose
     * rows led to "Job not found".
     */
    List<Job> findByCreatedByRecruiter_IdOrderByCreatedAtDesc(Long recruiterId);
}
