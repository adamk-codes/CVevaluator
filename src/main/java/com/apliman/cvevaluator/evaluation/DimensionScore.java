package com.apliman.cvevaluator.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A 0-5 score on one {@link ScoreDimension}, with the argument for it.
 *
 * <p>Same ordering rule as {@link RequirementAssessment} and for the same
 * reason: {@code reasoning} comes before {@code score} so the model writes the
 * case before it writes the number. A score generated first is a number the
 * rest of the object then has to agree with. As there, the
 * {@code @JsonPropertyOrder} is what enforces it — the generator would
 * otherwise sort alphabetically and put {@code evidenceQuote} ahead of both.
 *
 * @param dimension     which of the two, named before anything is said about it
 * @param reasoning     the argument, written before the score exists
 * @param score         0-5 inclusive. Validated in {@code LlmEvaluator} rather
 *                      than trusted — a model that returns 7 is telling you it
 *                      ignored the anchors, and silently clamping that to 5
 *                      would hide it.
 * @param evidenceQuote a verbatim span from the CV, or {@code null} when the CV
 *                      offers nothing to point at. A 0 with no quote is the
 *                      normal case: it means there was nothing to show.
 */
@JsonPropertyOrder({"dimension", "reasoning", "score", "evidenceQuote"})
public record DimensionScore(
        ScoreDimension dimension,
        String reasoning,
        int score,
        @JsonProperty(required = false) String evidenceQuote
) {
}
