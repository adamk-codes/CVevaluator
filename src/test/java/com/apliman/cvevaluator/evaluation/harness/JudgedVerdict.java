package com.apliman.cvevaluator.evaluation.harness;

import com.apliman.cvevaluator.evaluation.Verdict;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * What the judge model returns: an argument, then a verdict.
 *
 * <p>{@code @JsonPropertyOrder} for the same reason
 * {@code RequirementAssessment} carries one — Spring AI's schema generator
 * sorts properties alphabetically, which would put {@code verdict} before
 * {@code reasoning} and let the model commit to an answer before writing the
 * case for it.
 *
 * <p>The reasoning is not decoration here. If a judged verdict ever ships, it
 * is the thing that keeps a decision arguable: "you are a possible fit because
 * the only must-have you miss is Kubernetes, which the CV never discusses" can
 * be challenged on the facts. A bare verdict cannot.
 */
@JsonPropertyOrder({"reasoning", "verdict"})
public record JudgedVerdict(String reasoning, Verdict verdict) {
}
