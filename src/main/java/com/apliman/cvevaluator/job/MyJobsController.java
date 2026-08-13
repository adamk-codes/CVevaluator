package com.apliman.cvevaluator.job;

import com.apliman.cvevaluator.job.dto.JobResponse;
import com.apliman.cvevaluator.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The postings the signed-in recruiter owns.
 *
 * <h2>What this fixes</h2>
 *
 * The recruiter dashboard used to call {@code GET /api/jobs}, which returns
 * every posting on the platform because candidates browse the same endpoint to
 * find something to apply to. Reading a job's applicants, though, is scoped to
 * the recruiter who posted it. So the dashboard listed jobs whose applicant
 * list then answered "Job not found" — the row was real, the recruiter simply
 * had no business opening it.
 *
 * <p>Two endpoints answering "which jobs" differently is the actual defect;
 * neither was individually wrong. This is the one a recruiter's own screens
 * ask, and {@code GET /api/jobs} stays the candidate's browse.
 *
 * <p>Takes no id, for the same reason {@code MyApplicationsController} does
 * not: the subject is the token's, so there is no parameter to point at
 * somebody else's postings.
 */
@RestController
@RequestMapping("/api/me")
public class MyJobsController {

    private final JobRepository jobs;
    private final CurrentUserProvider currentUser;

    public MyJobsController(JobRepository jobs, CurrentUserProvider currentUser) {
        this.jobs = jobs;
        this.currentUser = currentUser;
    }

    /**
     * Newest first — a recruiter's dashboard opens on what they just posted,
     * not on what they posted first.
     */
    @GetMapping("/jobs")
    public List<JobResponse> mine() {
        return jobs.findByCreatedByRecruiter_IdOrderByCreatedAtDesc(currentUser.currentUserId())
                .stream()
                .map(JobResponse::from)
                .toList();
    }
}
