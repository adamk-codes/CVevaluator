package com.apliman.cvevaluator.evaluation;

import java.util.List;

/**
 * One complete evaluation of one CV against one job, as
 * {@code LlmEvaluator.evaluate} returns it.
 *
 * <p>Not what the model returns. The model produces the assessments, the scores
 * and the summary ({@link LlmEvaluationResponse}); everything else on this
 * record is added here — {@code verdict} is computed by
 * {@link VerdictCalculator}, and the last four fields are measurements of the
 * call rather than output of it.
 *
 * @param assessments                         exactly one per authored
 *                                            requirement, in the job's order
 * @param dimensionScores                     one per {@link ScoreDimension}
 * @param verdict                             computed, never generated
 * @param summary                             a few sentences for a human, the
 *                                            only free prose in the output
 * @param promptVersion                       which rubric produced this. Two
 *                                            evaluations are only comparable if
 *                                            this matches; without it, a rubric
 *                                            edit silently rewrites the meaning
 *                                            of every historical score.
 * @param evaluatedAgainstRequirementsVersion the job's {@code requirementsVersion}
 *                                            at the moment of the call. Pairs
 *                                            with {@code promptVersion}: one
 *                                            pins what was asked, the other pins
 *                                            how it was judged.
 * @param tokensIn                            prompt tokens as the provider
 *                                            reported them. {@code 0} means the
 *                                            provider reported no usage at all —
 *                                            a real call is never zero.
 * @param tokensOut                           completion tokens, same convention
 * @param latencyMs                           wall clock around the model call
 *                                            only, excluding validation and
 *                                            verdict computation
 */
public record EvaluationResult(
        List<RequirementAssessment> assessments,
        List<DimensionScore> dimensionScores,
        Verdict verdict,
        String summary,
        String promptVersion,
        int evaluatedAgainstRequirementsVersion,
        int tokensIn,
        int tokensOut,
        long latencyMs
) {
}
