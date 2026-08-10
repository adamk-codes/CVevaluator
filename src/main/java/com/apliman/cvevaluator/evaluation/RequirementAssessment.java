package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.job.RequirementKind;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The model's answer on one authored requirement.
 *
 * <h2>Field order is load-bearing — do not reorder</h2>
 *
 * The JSON schema is appended to the prompt and the model fills the object in
 * the order the schema lists it, one token after another. So {@code reasoning}
 * comes <em>before</em> {@code status}: by the time the model commits to a
 * verdict it has already written out the argument for it. Put {@code status}
 * first and the reasoning becomes a justification composed after the answer is
 * fixed, which reads identically and is worth nothing.
 *
 * <p><strong>{@code @JsonPropertyOrder} is what makes that true, and it is not
 * decoration.</strong> Spring AI's schema generator emits properties in
 * <em>alphabetical</em> order, not declaration order. Without the annotation
 * this record's schema reads
 * {@code evidenceQuote, reasoning, requirementId, requirementKind,
 * requirementText, status} — the model would pick a quote first and decide the
 * status last, which is the exact inversion of the design. Declaration order
 * below is kept in step with the annotation so the file reads correctly, but
 * the annotation is the part the model sees. Verified by
 * {@code EvaluationSchemaTest}.
 *
 * <p>The same applies to {@code evidenceQuote} sitting last. The quote is
 * chosen to support a status that has already been stated, so a model that
 * cannot find one has to either return null or contradict itself — both of
 * which are visible. A quote written first would drag the status along behind
 * it.
 *
 * @param requirementId   echoed verbatim from the job's authored list. This is
 *                        the join back to what was asked, so an invented or
 *                        altered id fails validation rather than being repaired.
 * @param requirementText the requirement as it read when this assessment was
 *                        made. Denormalized on purpose: requirements are
 *                        replaced wholesale on edit and ids are reused across
 *                        versions, so without a snapshot here a later edit would
 *                        make a stored evaluation display text that was never
 *                        evaluated. Written from the authored list, not from the
 *                        model's echo — see {@code LlmEvaluator}.
 * @param requirementKind snapshot of MUST_HAVE / NICE_TO_HAVE for the same
 *                        reason, and what {@link VerdictCalculator} weighs.
 * @param reasoning       the argument, written before the verdict exists
 * @param status          see {@link RequirementStatus}
 * @param evidenceQuote   a span copied character for character out of the CV
 *                        text the model was shown, or {@code null}. Nullable is
 *                        not laziness: {@code NOT_MET} and {@code UNCLEAR} must
 *                        have it null, because an absence has nothing to quote.
 *                        {@code @JsonProperty(required = false)} keeps it out of
 *                        the schema's {@code required} list — every other field
 *                        is listed there, and a schema demanding a quote while
 *                        the rubric forbids one is a contradiction the model
 *                        resolves by inventing something.
 */
@JsonPropertyOrder({
        "requirementId", "requirementText", "requirementKind",
        "reasoning", "status", "evidenceQuote"
})
public record RequirementAssessment(
        String requirementId,
        String requirementText,
        RequirementKind requirementKind,
        String reasoning,
        RequirementStatus status,
        @JsonProperty(required = false) String evidenceQuote
) {
}
