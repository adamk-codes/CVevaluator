package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.application.ApplicationNotFoundException;
import com.apliman.cvevaluator.application.ApplicationRepository;
import com.apliman.cvevaluator.evaluation.dto.EvaluationResponse;
import com.apliman.cvevaluator.evaluation.dto.RequirementAssessmentResponse;
import com.apliman.cvevaluator.security.CurrentUserProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reading evaluations back.
 *
 * <p>Mapped at {@code /api/applications/{applicationId}} rather than nested
 * under the job the way {@code ApplicationController} is. An application id is
 * globally unique, so requiring the job id in the path would make a caller
 * carry a value it does not need and cannot be wrong about in a useful way.
 * The cost is that the two application-related controllers do not sit at the
 * same prefix.
 *
 * <h2>Who can read any of this</h2>
 *
 * <p>Recruiters, and only the one who posted the job. Two rules stacked, and
 * they fail differently on purpose:
 *
 * <ul>
 *   <li>A <strong>candidate</strong> gets 403 from the filter chain before any
 *       method here runs — {@code /api/applications/**} is RECRUITER-only in
 *       {@code SecurityConfig}. Candidates never see the model's assessment of
 *       their own CV: not the verdict, not the dimension scores, not the
 *       per-requirement reasoning. That is a product decision. The 403 is
 *       identical whether or not the id exists, so the blanket refusal
 *       discloses nothing.</li>
 *   <li>A <strong>recruiter who does not own the job</strong> gets 404, from
 *       {@link #application}. Not 403 — a 403 here would confirm the
 *       application exists, which is the whole thing worth hiding.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/applications/{applicationId}")
public class EvaluationController {

    private final EvaluationService evaluations;
    private final ApplicationRepository applications;
    private final CurrentUserProvider currentUser;

    public EvaluationController(
            EvaluationService evaluations,
            ApplicationRepository applications,
            CurrentUserProvider currentUser
    ) {
        this.evaluations = evaluations;
        this.applications = applications;
        this.currentUser = currentUser;
    }

    /**
     * The current evaluation, in full — every assessment with its reasoning and
     * quote, both dimension scores, the verdict and the summary.
     */
    @GetMapping("/evaluation")
    public EvaluationResponse latest(@PathVariable Long applicationId) {
        return EvaluationResponse.from(latestFor(applicationId));
    }

    /**
     * The retained history, newest first — at most
     * {@code cvevaluator.evaluation.max-per-application} entries.
     *
     * <p>Returns an empty list rather than a 404 when there are no evaluations,
     * unlike {@link #latest}. Asking for a collection and being told there are
     * none of them is a normal answer; asking for <em>the</em> evaluation and
     * getting a body full of nulls is not.
     */
    @GetMapping("/evaluations")
    public List<EvaluationResponse> history(@PathVariable Long applicationId) {
        return evaluations.findHistory(application(applicationId))
                .stream()
                .map(EvaluationResponse::from)
                .toList();
    }

    /**
     * Why one specific requirement came out the way it did.
     *
     * <p>The endpoint a recruiter reaches for on seeing a status they want to
     * argue with — "why is R2 only PARTIAL?" The reasoning, the status and the
     * quote backing it are all in the response, along with the requirement text
     * as it read when it was assessed, so the answer stands on its own without
     * the reader having to reconstruct which version of the requirements it was
     * judged against.
     *
     * <p>Reads the latest evaluation only. An older one may well have assessed
     * the same id against different text, which is exactly the confusion this
     * would create if it searched the history.
     */
    @GetMapping("/evaluation/requirements/{requirementId}")
    public RequirementAssessmentResponse assessment(
            @PathVariable Long applicationId,
            @PathVariable String requirementId
    ) {
        Evaluation evaluation = latestFor(applicationId);

        // Case-sensitive on purpose. Requirement ids are echoed verbatim by the
        // model and validated against the job's authored list, so "r2" is not a
        // typo to be helpfully corrected - it is an id that does not exist.
        return evaluation.getAssessments().stream()
                .filter(assessment -> assessment.requirementId().equals(requirementId))
                .findFirst()
                .map(assessment -> RequirementAssessmentResponse.from(evaluation, assessment))
                .orElseThrow(() -> EvaluationNotFoundException.forRequirement(applicationId, requirementId));
    }

    /**
     * Discards an evaluation the recruiter no longer wants.
     *
     * <p>204 with no body: there is nothing meaningful to return, and the
     * client already knows what it deleted.
     *
     * <p>Not idempotent in the strict sense — deleting the same id twice gives
     * 204 then 404. That is the more useful answer here: a recruiter who gets a
     * 404 on a delete has learned they were looking at a stale list, which a
     * silent second 204 would hide.
     *
     * <p>Scoped under the application in the path, and the service checks the
     * evaluation actually belongs to it. Without that check a guessed id would
     * delete another candidate's evaluation.
     */
    @DeleteMapping("/evaluations/{evaluationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long applicationId, @PathVariable Long evaluationId) {
        evaluations.delete(application(applicationId), evaluationId);
    }

    private Evaluation latestFor(Long applicationId) {
        return evaluations.findLatest(application(applicationId))
                .orElseThrow(() -> EvaluationNotFoundException.forApplication(applicationId));
    }

    /**
     * Resolved rather than assumed, so an unknown application id is a 404 that
     * says so instead of an empty evaluation list that reads as "never
     * evaluated".
     *
     * <p>Scoped to the caller's own postings, and that scoping is the single
     * choke point for every endpoint on this controller — reads, the
     * per-requirement lookup and the delete all go through here. Adding an
     * endpoint that resolves the application some other way is the way to
     * reintroduce the hole, so: resolve it here.
     *
     * <p>"Not found" and "not yours" are one answer. See
     * {@link ApplicationRepository#findByIdAndJob_CreatedByRecruiter_Id}.
     */
    private Application application(Long applicationId) {
        return applications
                .findByIdAndJob_CreatedByRecruiter_Id(applicationId, currentUser.currentUserId())
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }
}
