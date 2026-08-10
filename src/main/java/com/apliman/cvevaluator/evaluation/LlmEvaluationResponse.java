package com.apliman.cvevaluator.evaluation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * Exactly what the model is asked to produce — no more.
 *
 * <p>A separate type from {@link EvaluationResult} because the difference
 * between them is the point: no {@code verdict}, no {@code promptVersion}, no
 * token counts. A field on this record is a field the model can influence, so
 * keeping it minimal is what makes "the model never emits the verdict" a
 * property of the type rather than a promise in a prompt.
 *
 * <p>It is also a record wrapping the two lists rather than the lists
 * themselves. Native provider structured output rejects a top-level JSON array
 * schema, so {@code .entity(List.class)} cannot work — the wrapper is required,
 * not stylistic.
 *
 * <p>Order again, and again enforced by {@code @JsonPropertyOrder} rather than
 * by declaration: both lists come before {@code summary}, so the summary is
 * written last, over assessments that are already on the page. Alphabetical
 * sorting would put {@code dimensionScores} first, which is harmless here but
 * left to chance — the annotation says what is intended.
 *
 * @param requirementAssessments one per requirement id supplied, same order
 * @param dimensionScores        one per {@link ScoreDimension}
 * @param summary                a short human-readable synthesis
 */
@JsonPropertyOrder({"requirementAssessments", "dimensionScores", "summary"})
public record LlmEvaluationResponse(
        List<RequirementAssessment> requirementAssessments,
        List<DimensionScore> dimensionScores,
        String summary
) {
}
