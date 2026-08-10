package com.apliman.cvevaluator.evaluation;

/**
 * The single headline answer for one CV against one job.
 *
 * <p>Computed by {@link VerdictCalculator} from the requirement assessments.
 * <strong>The model never emits this.</strong> If it did, the same set of
 * assessments could produce different headlines on two runs, and the number a
 * recruiter actually acts on would be the one part of the output with nothing
 * behind it.
 */
public enum Verdict {

    /** Clears every must-have and brings most of the nice-to-haves. */
    STRONG_FIT,

    /** Clears every must-have; the rest is thin. Worth a conversation. */
    POSSIBLE_FIT,

    /** No must-have outright failed, but the coverage is weak throughout. */
    WEAK_FIT,

    /** At least one must-have is not satisfied, or the CV is silent on it. */
    NOT_A_FIT
}
