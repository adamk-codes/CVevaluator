package com.apliman.cvevaluator.job;

/**
 * How much weight one authored requirement carries.
 *
 * <p>The distinction is deliberately binary rather than a numeric weight.
 * A recruiter can answer "is a candidate without this disqualified?" out loud;
 * nobody can defend the difference between 0.7 and 0.8 in a demo. The cap of
 * five {@link #MUST_HAVE}s in {@link JobRequirementsValidator} exists to keep
 * that answer honest — if everything is required, nothing is.
 */
public enum RequirementKind {

    /** A candidate lacking this is out, regardless of other strengths. */
    MUST_HAVE,

    /** Counts in the candidate's favour; its absence alone never disqualifies. */
    NICE_TO_HAVE
}
