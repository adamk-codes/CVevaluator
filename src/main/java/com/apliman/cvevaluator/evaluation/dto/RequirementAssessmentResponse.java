package com.apliman.cvevaluator.evaluation.dto;

import com.apliman.cvevaluator.evaluation.Evaluation;
import com.apliman.cvevaluator.evaluation.RequirementAssessment;

import java.time.Instant;

/**
 * One requirement's assessment, with enough context to know what it came from.
 *
 * <p>The answer to "why did R2 come out PARTIAL?". The assessment alone would
 * be the literal answer, but on its own it is unanswerable in the way that
 * matters: requirement ids are reused across requirement versions, so an
 * assessment of {@code R2} means nothing without the version it was made
 * against. {@code promptVersion} is here for the same reason on the other axis
 * — the same CV and the same requirement can come out differently under a
 * different rubric, and if that happens it should be visible rather than
 * mysterious.
 *
 * @param assessment carries the reasoning, the status, and the verbatim quote
 *                   if there is one — see {@link RequirementAssessment}
 */
public record RequirementAssessmentResponse(
        Long evaluationId,
        Long applicationId,
        String promptVersion,
        int evaluatedAgainstRequirementsVersion,
        Instant evaluatedAt,
        RequirementAssessment assessment
) {

    public static RequirementAssessmentResponse from(Evaluation evaluation, RequirementAssessment assessment) {
        return new RequirementAssessmentResponse(
                evaluation.getId(),
                evaluation.getApplication().getId(),
                evaluation.getPromptVersion(),
                evaluation.getRequirementsVersion(),
                evaluation.getCreatedAt(),
                assessment);
    }
}
