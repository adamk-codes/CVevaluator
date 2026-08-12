package com.apliman.cvevaluator.evaluation.dto;

import com.apliman.cvevaluator.evaluation.DimensionScore;
import com.apliman.cvevaluator.evaluation.Evaluation;
import com.apliman.cvevaluator.evaluation.RequirementAssessment;
import com.apliman.cvevaluator.evaluation.Verdict;

import java.time.Instant;
import java.util.List;

/**
 * One stored evaluation on the wire.
 *
 * <p>{@link RequirementAssessment} and {@link DimensionScore} are passed
 * through rather than re-mapped. They are value records already — the same
 * ones the model fills in and the ones stored as {@code jsonb} — not entities,
 * so the rule that keeps entities off the wire is not in play. Re-declaring
 * them here would create two shapes that must be kept in step for no gain, and
 * the ordering annotations that make {@code reasoning} come before
 * {@code status} would have to be duplicated too.
 *
 * <p>{@code tokensIn}, {@code tokensOut} and {@code latencyMs} are included on
 * purpose. They are operational rather than editorial and a recruiter will
 * ignore them, but being able to point at what an assessment cost and how long
 * it took is worth more at a demo than the tidiness of hiding them.
 */
public record EvaluationResponse(
        Long id,
        Long applicationId,
        Verdict verdict,
        String summary,
        List<RequirementAssessment> assessments,
        List<DimensionScore> dimensionScores,
        String promptVersion,
        int evaluatedAgainstRequirementsVersion,
        int tokensIn,
        int tokensOut,
        long latencyMs,
        Instant createdAt
) {

    public static EvaluationResponse from(Evaluation evaluation) {
        return new EvaluationResponse(
                evaluation.getId(),
                evaluation.getApplication().getId(),
                evaluation.getVerdict(),
                evaluation.getSummary(),
                evaluation.getAssessments(),
                evaluation.getDimensionScores(),
                evaluation.getPromptVersion(),
                evaluation.getRequirementsVersion(),
                evaluation.getTokensIn(),
                evaluation.getTokensOut(),
                evaluation.getLatencyMs(),
                evaluation.getCreatedAt());
    }
}
