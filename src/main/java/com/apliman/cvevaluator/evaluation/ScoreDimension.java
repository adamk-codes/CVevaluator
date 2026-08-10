package com.apliman.cvevaluator.evaluation;

/**
 * The two things scored on a 0-5 scale in addition to the per-requirement pass.
 *
 * <p>Two, not ten. Every dimension is a separate reasoning step and a separate
 * quote the grounding checker has to verify, so the list is short on purpose.
 * These two were kept because neither is expressible as a requirement: a
 * recruiter can write "5 years of Java" as a checkable line, but not "writes
 * about their work in a way that shows they understood it".
 *
 * <p>Deliberately <em>not</em> part of the verdict. {@link VerdictCalculator}
 * reads the requirement assessments only — see the note there on why a rubric
 * score is not allowed to rescue a failed must-have.
 */
public enum ScoreDimension {

    /**
     * Did the candidate own outcomes, or only participate? Scored from what the
     * CV says was achieved and who is claimed to have done it.
     */
    IMPACT_AND_OWNERSHIP,

    /**
     * How clearly the work is described: specific, concrete, and honest about
     * scope, versus generic phrasing that would fit any candidate.
     */
    COMMUNICATION_QUALITY
}
