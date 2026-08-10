package com.apliman.cvevaluator.evaluation;

/**
 * The verdict on one authored requirement against one CV.
 *
 * <p>Four values, not three, and the fourth is the one that earns its place.
 * {@link #NOT_MET} and {@link #UNCLEAR} look the same on a scorecard and are
 * completely different claims: {@code NOT_MET} says the CV shows the
 * requirement is <em>not</em> satisfied, {@code UNCLEAR} says the CV never
 * mentions it. Collapsing them would make the grader assert something the CV
 * does not say, which is the one failure mode this whole design exists to
 * prevent.
 *
 * <p>Neither of them may carry an evidence quote — an absence cannot be quoted.
 * {@code LlmEvaluator} enforces that rather than trusting the rubric to be
 * obeyed.
 */
public enum RequirementStatus {

    /** Direct evidence in the CV. */
    MET,

    /** Related evidence, but weaker, shorter or narrower than what was asked. */
    PARTIAL,

    /** The CV shows the requirement is not satisfied. */
    NOT_MET,

    /** The CV is silent on it. */
    UNCLEAR
}
