package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.application.dto.ApplicationStatusResponse;
import com.apliman.cvevaluator.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What the signed-in candidate has submitted, across every job.
 *
 * <h2>Why this is its own controller</h2>
 *
 * {@link ApplicationController} is mapped at
 * {@code /api/jobs/{jobId}/applications}, and a class-level
 * {@code @RequestMapping} cannot be escaped by a method — so this could not
 * live there however well it fits the subject. Nesting it under a job would be
 * wrong anyway: the defining scope is the <em>candidate</em>, and the answer
 * spans jobs.
 *
 * <h2>Why it takes no id</h2>
 *
 * There is no {@code /api/candidates/{id}/applications}. The only person who
 * may read this list is its owner, so an id in the path would be a parameter
 * with exactly one legal value — and one that a caller would inevitably try to
 * change. Reading the subject from the token instead means there is nothing to
 * tamper with: {@code /api/me/applications} returns your applications because
 * there is no way to ask it for anyone else's.
 */
@RestController
@RequestMapping("/api/me")
public class MyApplicationsController {

    private final ApplicationRepository applications;
    private final CurrentUserProvider currentUser;

    public MyApplicationsController(
            ApplicationRepository applications,
            CurrentUserProvider currentUser
    ) {
        this.applications = applications;
        this.currentUser = currentUser;
    }

    /**
     * Every CV this candidate submitted, newest first.
     *
     * <p>Carries no verdict, and that is the product decision rather than an
     * omission: a candidate sees that their CV arrived and was read, never the
     * assessment of it. The evaluation endpoints are recruiter-only for the
     * same reason — see the note in {@code SecurityConfig}.
     *
     * <p>An empty list rather than a 404 when nothing has been submitted. A
     * candidate with no applications is a normal state with a screen of its
     * own, not a missing resource.
     */
    @GetMapping("/applications")
    public List<ApplicationStatusResponse> mine() {
        return applications.findSummariesByCandidateId(currentUser.currentUserId());
    }
}
