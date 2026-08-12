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
 * <h2>Why counting gaps rather than scoring them</h2>
 *
 * The first version of this class returned NOT_A_FIT the moment any single
 * must-have was unsatisfied. That is defensible on its face and was measurably
 * wrong: across 57 fixture pairs it produced only two of the four verdicts,
 * because "a named gap in a required area" — the definition of a borderline
 * candidate — is precisely a missed must-have. POSSIBLE_FIT and WEAK_FIT were
 * unreachable, and the resulting 40/57 agreement was barely above the 34/57 a
 * rule that always answers NOT_A_FIT would score.
 *
 * <p>The bands below are read off the <em>count</em> of missed must-haves
 * instead, which is what the band definitions actually describe. Eight rules
 * were scored against the same corpus; this one and its nearest variant tied at
 * 48/57, and it was chosen over the variant because every branch maps to a
 * sentence you can say out loud. An LLM asked to pick the verdict from the same
 * assessments reached 47/57 at best, while costing money per verdict and giving
 * up reproducibility.
 *
 * <p>Worth knowing: no rule tried, and no model asked, got more than 3 of 7
 * borderline cases right. That ceiling is not in this class — it is in the
 * assessments it is handed, or in the labels being argued against.
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
     * Nice-to-have coverage that separates {@code STRONG_FIT} from
     * {@code POSSIBLE_FIT} once every must-have is met.
     *
     * <p>Meeting the required list and bringing nothing else is not a strong
     * fit — the candidate clears the bar and stops there. A quarter of the
     * nice-to-haves is the smallest showing that reads as more than that.
     */
    private static final double STRONG_FIT_NICE_TO_HAVE_FLOOR = 0.25;

    /**
     * Overall coverage below which a candidate is a different profession rather
     * than an under-qualified one.
     *
     * <p>This is the constant that makes {@code WEAK_FIT} reachable at all.
     * "Same broad domain, clearly under-qualified" and "different profession"
     * both fail every must-have, so must-haves alone cannot separate a junior
     * backend developer from a restaurant manager applying to the same backend
     * job. Coverage across <em>every</em> requirement can: the junior developer
     * still matches nice-to-haves the restaurant manager does not.
     *
     * <p>0.15 is deliberately low. It answers "does this CV match this job in
     * any respect at all", and one solid hit out of a dozen requirements is
     * enough to say yes.
     */
    private static final double DOMAIN_FLOOR = 0.15;

    /**
     * Fallback band cuts, used only when a job has no must-haves — which the
     * API does not currently allow. Kept so that case has a defined answer
     * rather than an accidental one.
     */
    private static final double STRONG_FIT_THRESHOLD = 0.85;

    /** @see #STRONG_FIT_THRESHOLD */
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

        long mustHaves = countOf(assessments, RequirementKind.MUST_HAVE);
        long missed = countMissedMustHaves(assessments);

        if (mustHaves == 0) {
            // No must-haves at all. The API does not allow this today; scored
            // on nice-to-haves alone rather than left to fall through the
            // branches below, which all assume a must-have list exists.
            return band(coverageOrZero(assessments, RequirementKind.NICE_TO_HAVE));
        }

        if (missed == 0) {
            // Everything required is met, which is "meets the required list
            // with evidence". Nice-to-haves separate a candidate worth
            // shortlisting from one who clears the bar and brings nothing else.
            return coverageOrZero(assessments, RequirementKind.NICE_TO_HAVE) >= STRONG_FIT_NICE_TO_HAVE_FLOOR
                    ? Verdict.STRONG_FIT
                    : Verdict.POSSIBLE_FIT;
        }

        if (missed == 1) {
            // Exactly "real overlap but a named gap in a required area" - the
            // case a human would argue about. Counting the gaps rather than
            // scoring them matters here: one gap out of two and one out of five
            // are different situations that a ratio flattens.
            return Verdict.POSSIBLE_FIT;
        }

        if (missed < mustHaves) {
            // Several gaps, but not everything. Under-qualified rather than
            // unqualified.
            return Verdict.WEAK_FIT;
        }

        // Nothing required is met. This is the only branch that can reach
        // NOT_A_FIT, and it still has to prove the candidate is in a different
        // field rather than merely short of every bar - see DOMAIN_FLOOR.
        return overallCoverage(assessments) >= DOMAIN_FLOOR ? Verdict.WEAK_FIT : Verdict.NOT_A_FIT;
    }

    /**
     * A must-have the CV does not satisfy on the evidence available.
     *
     * <p>NOT_MET and UNCLEAR are different findings that count the same here:
     * "your CV shows you do not have this" and "your CV does not say whether you
     * have this" both leave the requirement unproven. Treating UNCLEAR as
     * satisfied would reward silence over an honest admission — and measurement
     * backs that up, since giving UNCLEAR partial credit made every candidate
     * look like the same profession and collapsed NOT_A_FIT accuracy from 34/34
     * to 18/34 on the fixture corpus.
     */
    private static boolean isMissed(RequirementAssessment assessment) {
        return assessment.status() == RequirementStatus.NOT_MET
                || assessment.status() == RequirementStatus.UNCLEAR;
    }

    private static long countMissedMustHaves(List<RequirementAssessment> assessments) {
        return assessments.stream()
                .filter(a -> a.requirementKind() == RequirementKind.MUST_HAVE)
                .filter(VerdictCalculator::isMissed)
                .count();
    }

    private static long countOf(List<RequirementAssessment> assessments, RequirementKind kind) {
        return assessments.stream().filter(a -> a.requirementKind() == kind).count();
    }

    /**
     * Credit earned over credit available across every requirement, both kinds.
     *
     * <p>The domain signal. Unlike {@link #coverageOf} this deliberately ignores
     * the must-have/nice-to-have split, because the question it answers —
     * "does this CV relate to this job at all" — does not care which list a
     * match came from.
     */
    private static double overallCoverage(List<RequirementAssessment> assessments) {
        double earned = 0.0;
        for (RequirementAssessment assessment : assessments) {
            if (assessment.status() == RequirementStatus.MET) {
                earned += MET_CREDIT;
            } else if (assessment.status() == RequirementStatus.PARTIAL) {
                earned += PARTIAL_CREDIT;
            }
        }
        return assessments.isEmpty() ? 0.0 : earned / assessments.size();
    }

    /** As {@link #coverageOf}, but a job with none of that kind scores 0 rather than NaN. */
    private static double coverageOrZero(List<RequirementAssessment> assessments, RequirementKind kind) {
        double value = coverageOf(assessments, kind);
        return Double.isNaN(value) ? 0.0 : value;
    }

    /** The fallback band cuts, reached only by the no-must-haves branch. */
    private static Verdict band(double score) {
        if (score >= STRONG_FIT_THRESHOLD) {
            return Verdict.STRONG_FIT;
        }
        if (score >= POSSIBLE_FIT_THRESHOLD) {
            return Verdict.POSSIBLE_FIT;
        }
        return Verdict.WEAK_FIT;
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
