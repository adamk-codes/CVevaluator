package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.application.Application;
import com.apliman.cvevaluator.application.ApplicationNotFoundException;
import com.apliman.cvevaluator.application.ApplicationRepository;
import com.apliman.cvevaluator.evaluation.dto.EvaluationResponse;
import com.apliman.cvevaluator.evaluation.dto.RequirementAssessmentResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
 */
@RestController
@RequestMapping("/api/applications/{applicationId}")
public class EvaluationController {

    private final EvaluationService evaluations;
    private final ApplicationRepository applications;

    public EvaluationController(EvaluationService evaluations, ApplicationRepository applications) {
        this.evaluations = evaluations;
        this.applications = applications;
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

    private Evaluation latestFor(Long applicationId) {
        return evaluations.findLatest(application(applicationId))
                .orElseThrow(() -> EvaluationNotFoundException.forApplication(applicationId));
    }

    /**
     * Resolved rather than assumed, so an unknown application id is a 404 that
     * says so instead of an empty evaluation list that reads as "never
     * evaluated".
     */
    private Application application(Long applicationId) {
        return applications.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }
}
