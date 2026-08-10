package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.job.RequirementKind;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns a list of requirement assessments into one {@link Verdict}. Pure Java,
 * no model call, no state.
 *
 * <h2>Why this is not the model's job</h2>
 *
 * The verdict is the only part of the output a recruiter acts on directly, so
 * it is the part that most needs to be reproducible. Asking the model for it
 * would mean the same set of assessments could yield {@code STRONG_FIT} on
 * Monday and {@code POSSIBLE_FIT} on Tuesday with nothing in the data to
 * explain the difference. Computing it here means the headline is a function of
 * the evidence: to change the verdict you have to change an assessment, and the
 * assessment is quoted.
 *
 * <p>It also makes the rule arguable. "You were marked NOT_A_FIT because R2 is
 * a must-have and your CV does not mention it" is a sentence a candidate can
 * challenge on the facts. "The model felt you were a weak fit" is not.
 *
 * <h2>What is deliberately not an input</h2>
 *
 * {@link DimensionScore} does not appear anywhere in this class. The two rubric
 * dimensions are judgements about how a CV is written, and letting them move
 * the verdict would mean a well-written CV could outrank one that actually
 * meets the requirements. They are shown next to the verdict, not folded into
 * it.
 */
@Component
public class VerdictCalculator {

    /**
     * A {@link RequirementStatus#MET} must-have or nice-to-have counts fully.
     * Named only so the {@code PARTIAL} weight below has something to be half
     * of.
     */
    private static final double MET_CREDIT = 1.0;

    /**
     * {@code PARTIAL} counts half. Half rather than a tuned number because
     * {@code PARTIAL} means "related but weaker" and there is no honest way to
     * say whether a particular instance is worth 0.4 or 0.6 — the model is not
     * calibrated to that resolution and neither is the rubric. Half is the only
     * value that needs no defence beyond "it is between the two".
     */
    private static final double PARTIAL_CREDIT = 0.5;

    /**
     * Must-haves are four fifths of the score. The split is deliberately lopsided
     * rather than 50/50: a job's must-haves are capped at five and were written
     * to be the things that actually decide the hire, so a candidate strong on
     * the nice-to-haves and thin on the must-haves should not be able to
     * average their way to a good headline. At 0.8/0.2 the nice-to-haves can
     * separate two candidates who clear the same bar, and nothing more.
     */
    private static final double MUST_HAVE_WEIGHT = 0.8;

    /** The remainder. Kept as a named constant so the two are read together. */
    private static final double NICE_TO_HAVE_WEIGHT = 0.2;

    /**
     * At or above this, {@code STRONG_FIT}. Set at 0.85 rather than 0.80 for one
     * specific reason: a candidate who meets every must-have and none of the
     * nice-to-haves scores exactly 0.80, and that candidate is not a strong fit
     * — they clear the bar and bring nothing else. 0.85 puts them in
     * {@code POSSIBLE_FIT}, which is what a recruiter reading the assessments
     * would say.
     */
    private static final double STRONG_FIT_THRESHOLD = 0.85;

    /**
     * At or above this, {@code POSSIBLE_FIT}; below it, {@code WEAK_FIT}. 0.55
     * sits just above the 0.5 that a CV scoring {@code PARTIAL} on absolutely
     * everything would earn. That is the intended boundary: half-credit across
     * the board is a weak fit, not a possible one, and the gap between the two
     * numbers is what stops a rounding difference from deciding it.
     */
    private static final double POSSIBLE_FIT_THRESHOLD = 0.55;

    /**
     * @param assessments one per authored requirement; may be empty but not null
     * @return the headline verdict for this evaluation
     */
    public Verdict calculate(List<RequirementAssessment> assessments) {
        if (assessments == null || assessments.isEmpty()) {
            // Unreachable through the API - JobRequirementsValidator refuses a
            // job with no requirements - so this is the defensive branch.
            // WEAK_FIT and not NOT_A_FIT: nothing failed, there was simply
            // nothing to find evidence for, and NOT_A_FIT is an accusation that
            // should only follow a specific missing must-have.
            return Verdict.WEAK_FIT;
        }

        // The hard gate, checked before any arithmetic. NOT_MET and UNCLEAR are
        // different findings but have the same consequence here: for a
        // must-have, "your CV shows you do not have this" and "your CV does not
        // say whether you have this" both mean the requirement is unsatisfied
        // on the evidence available. Treating UNCLEAR as a pass would mean
        // silence is rewarded over an honest admission.
        for (RequirementAssessment assessment : assessments) {
            if (assessment.requirementKind() == RequirementKind.MUST_HAVE
                    && (assessment.status() == RequirementStatus.NOT_MET
                    || assessment.status() == RequirementStatus.UNCLEAR)) {
                return Verdict.NOT_A_FIT;
            }
        }

        double mustHaveCoverage = coverageOf(assessments, RequirementKind.MUST_HAVE);
        double niceToHaveCoverage = coverageOf(assessments, RequirementKind.NICE_TO_HAVE);

        double score = combine(mustHaveCoverage, niceToHaveCoverage);

        if (score >= STRONG_FIT_THRESHOLD) {
            return Verdict.STRONG_FIT;
        }
        if (score >= POSSIBLE_FIT_THRESHOLD) {
            return Verdict.POSSIBLE_FIT;
        }
        return Verdict.WEAK_FIT;
    }

    /**
     * Weighted sum, except when one side of it does not exist.
     *
     * <p>A job with no nice-to-haves at all is scored on its must-haves alone.
     * The alternative — counting an empty nice-to-have list as 0.0 coverage —
     * would cap such a job at 0.8 and make {@code STRONG_FIT} unreachable no
     * matter how good the candidate is, which is a scoring bug that only shows
     * up on jobs nobody tested with. The mirror case (no must-haves) cannot
     * happen through the API but is handled the same way rather than dividing
     * by zero.
     */
    private static double combine(double mustHaveCoverage, double niceToHaveCoverage) {
        if (Double.isNaN(mustHaveCoverage)) {
            return Double.isNaN(niceToHaveCoverage) ? 0.0 : niceToHaveCoverage;
        }
        if (Double.isNaN(niceToHaveCoverage)) {
            return mustHaveCoverage;
        }
        return MUST_HAVE_WEIGHT * mustHaveCoverage + NICE_TO_HAVE_WEIGHT * niceToHaveCoverage;
    }

    /**
     * Credit earned over credit available for one kind of requirement.
     *
     * @return a value in 0.0-1.0, or {@code NaN} when the job has no requirement
     *         of this kind. NaN rather than 0.0 so {@link #combine} can tell
     *         "scored nothing" apart from "there was nothing to score".
     */
    private static double coverageOf(List<RequirementAssessment> assessments, RequirementKind kind) {
        double earned = 0.0;
        int available = 0;

        for (RequirementAssessment assessment : assessments) {
            if (assessment.requirementKind() != kind) {
                continue;
            }
            available++;
            if (assessment.status() == RequirementStatus.MET) {
                earned += MET_CREDIT;
            } else if (assessment.status() == RequirementStatus.PARTIAL) {
                earned += PARTIAL_CREDIT;
            }
        }

        return available == 0 ? Double.NaN : earned / available;
    }
}
