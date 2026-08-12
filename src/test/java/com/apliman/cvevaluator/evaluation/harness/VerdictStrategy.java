package com.apliman.cvevaluator.evaluation.harness;

import com.apliman.cvevaluator.evaluation.RequirementAssessment;
import com.apliman.cvevaluator.evaluation.RequirementStatus;
import com.apliman.cvevaluator.evaluation.Verdict;
import com.apliman.cvevaluator.job.RequirementKind;

import java.util.List;
import java.util.function.Function;

/**
 * Candidate rules for turning assessments into a verdict, so they can be scored
 * against each other before one is chosen.
 *
 * <h2>Why these live in the test tree</h2>
 *
 * Only one of these will ever ship — whichever the data supports. The rest exist
 * to make that choice arguable rather than asserted, and keeping them here means
 * {@code VerdictCalculator} does not accumulate dead alternatives behind flags.
 *
 * <h2>What the bands are supposed to mean</h2>
 *
 * Taken from {@code fixtures/manifest.json}, because that is what the labels
 * were written against and therefore what any rule is being scored on:
 *
 * <ul>
 *   <li><strong>STRONG</strong> — "Meets the required list with evidence; would
 *       shortlist."
 *   <li><strong>BORDERLINE</strong> — "Real overlap but a named gap in a
 *       required area; a human would argue about it."
 *   <li><strong>WEAK</strong> — "Same broad domain, clearly under-qualified, or
 *       transferable-only."
 *   <li><strong>NOT_A_FIT</strong> — "Different profession. No reasonable
 *       reading shortlists this."
 * </ul>
 *
 * <p>Two of those readings are what the shipped rule gets wrong, and both are
 * visible in the definitions rather than needing data to spot. BORDERLINE
 * <em>is</em> "a named gap in a required area" — that is a NOT_MET must-have, so
 * a rule that returns NOT_A_FIT on any NOT_MET must-have can never produce
 * BORDERLINE at all. And NOT_A_FIT means <em>different profession</em>, not
 * "missing a requirement" — conflating those two is what collapses a four-band
 * scale into two.
 */
public final class VerdictStrategy {

    /** A named rule. */
    public record Named(String name, String description, Function<List<RequirementAssessment>, Verdict> rule) {

        public Verdict apply(List<RequirementAssessment> assessments) {
            return rule.apply(assessments);
        }
    }

    private VerdictStrategy() {
    }

    /**
     * Every rule under consideration, shipped one first.
     *
     * <p>The shipped rule is included precisely because it is expected to lose.
     * A comparison that only shows the replacements winning against each other
     * says nothing about whether the change was worth making.
     */
    public static List<Named> all() {
        return List.of(
                // The real production class, not a copy of it. Any candidate
                // that fails to beat this row is not worth adopting, and a copy
                // could silently drift from what actually ships.
                new Named("SHIPPED (live)",
                        "com.apliman.cvevaluator.evaluation.VerdictCalculator, as deployed",
                        new com.apliman.cvevaluator.evaluation.VerdictCalculator()::calculate),
                new Named("old-hard-gate",
                        "The rule this replaced: any NOT_MET/UNCLEAR must-have -> NOT_A_FIT",
                        VerdictStrategy::shipped),
                new Named("gate-not-met-only",
                        "Gate fires on NOT_MET must-haves only; UNCLEAR no longer fatal",
                        VerdictStrategy::gateOnNotMetOnly),
                new Named("no-gate",
                        "No gate at all; weighted score with a NOT_A_FIT floor",
                        VerdictStrategy::noGate),
                new Named("must-coverage",
                        "Banded on must-have coverage; nice-to-haves only break the top tie",
                        VerdictStrategy::mustCoverage),
                new Named("gate-on-majority",
                        "NOT_A_FIT only when most must-haves fail; one gap lands POSSIBLE",
                        VerdictStrategy::gateOnMajority),
                new Named("domain-floor",
                        "gate-on-majority, but NOT_A_FIT also needs near-zero overall coverage",
                        VerdictStrategy::domainFloor),
                new Named("band-semantics",
                        "Counts missed must-haves directly: 0 -> STRONG/POSSIBLE, 1 -> POSSIBLE, some -> WEAK",
                        VerdictStrategy::bandSemantics),
                new Named("unclear-soft",
                        "band-semantics, but UNCLEAR earns quarter credit rather than zero",
                        VerdictStrategy::unclearSoft));
    }

    // --- the rule as shipped today ---------------------------------------

    private static Verdict shipped(List<RequirementAssessment> assessments) {
        if (assessments.isEmpty()) {
            return Verdict.WEAK_FIT;
        }
        for (RequirementAssessment assessment : assessments) {
            if (assessment.requirementKind() == RequirementKind.MUST_HAVE && isUnsatisfied(assessment)) {
                return Verdict.NOT_A_FIT;
            }
        }
        return band(0.8 * coverage(assessments, RequirementKind.MUST_HAVE)
                + 0.2 * coverageOrZero(assessments, RequirementKind.NICE_TO_HAVE));
    }

    // --- candidates -------------------------------------------------------

    /**
     * The smallest possible change: stop treating silence as disqualifying.
     *
     * <p>Expected to help little on its own. A borderline candidate usually has
     * a must-have the CV actively fails rather than merely omits, so the gate
     * still fires — but it isolates how much of the collapse is UNCLEAR alone.
     */
    private static Verdict gateOnNotMetOnly(List<RequirementAssessment> assessments) {
        if (assessments.isEmpty()) {
            return Verdict.WEAK_FIT;
        }
        for (RequirementAssessment assessment : assessments) {
            if (assessment.requirementKind() == RequirementKind.MUST_HAVE
                    && assessment.status() == RequirementStatus.NOT_MET) {
                return Verdict.NOT_A_FIT;
            }
        }
        return band(0.8 * coverage(assessments, RequirementKind.MUST_HAVE)
                + 0.2 * coverageOrZero(assessments, RequirementKind.NICE_TO_HAVE));
    }

    /**
     * No gate. The 0.8 weighting already means must-haves dominate, so the gate
     * may simply be redundant with the arithmetic it precedes.
     *
     * <p>NOT_A_FIT becomes what the manifest says it is — near-zero overlap —
     * rather than a punishment for one missing requirement.
     */
    private static Verdict noGate(List<RequirementAssessment> assessments) {
        if (assessments.isEmpty()) {
            return Verdict.WEAK_FIT;
        }
        return band(0.8 * coverage(assessments, RequirementKind.MUST_HAVE)
                + 0.2 * coverageOrZero(assessments, RequirementKind.NICE_TO_HAVE));
    }

    /**
     * Bands read straight off must-have coverage, with nice-to-haves demoted to
     * a tiebreak at the top only.
     *
     * <p>Closest to how the bands are worded: "meets the required list" is
     * full must-have coverage, "a named gap in a required area" is one short,
     * and "different profession" is nothing matching.
     */
    private static Verdict mustCoverage(List<RequirementAssessment> assessments) {
        if (assessments.isEmpty()) {
            return Verdict.WEAK_FIT;
        }
        double must = coverage(assessments, RequirementKind.MUST_HAVE);
        if (Double.isNaN(must)) {
            return band(coverageOrZero(assessments, RequirementKind.NICE_TO_HAVE));
        }

        if (must >= 0.999) {
            // Every must-have met. Nice-to-haves decide strong versus possible:
            // clearing the bar and bringing nothing else is not a strong fit.
            return coverageOrZero(assessments, RequirementKind.NICE_TO_HAVE) >= 0.25
                    ? Verdict.STRONG_FIT
                    : Verdict.POSSIBLE_FIT;
        }
        if (must >= 0.65) {
            return Verdict.POSSIBLE_FIT;
        }
        if (must >= 0.25) {
            return Verdict.WEAK_FIT;
        }
        return Verdict.NOT_A_FIT;
    }

    /**
     * Keeps a gate, but one that fires on a pattern rather than a single miss.
     *
     * <p>Preserves the property the original gate was protecting — you cannot
     * average your way past the must-haves — while letting one named gap land
     * where the manifest says it belongs.
     */
    private static Verdict gateOnMajority(List<RequirementAssessment> assessments) {
        if (assessments.isEmpty()) {
            return Verdict.WEAK_FIT;
        }
        long mustHaves = assessments.stream()
                .filter(a -> a.requirementKind() == RequirementKind.MUST_HAVE)
                .count();
        long unsatisfied = assessments.stream()
                .filter(a -> a.requirementKind() == RequirementKind.MUST_HAVE)
                .filter(VerdictStrategy::isUnsatisfied)
                .count();

        if (mustHaves > 0 && unsatisfied == mustHaves) {
            return Verdict.NOT_A_FIT;
        }
        if (mustHaves > 0 && unsatisfied * 2 > mustHaves) {
            return Verdict.WEAK_FIT;
        }
        return band(0.8 * coverage(assessments, RequirementKind.MUST_HAVE)
                + 0.2 * coverageOrZero(assessments, RequirementKind.NICE_TO_HAVE));
    }

    /**
     * The idea the other rules are missing: WEAK versus NOT_A_FIT is a question
     * about <em>domain</em>, not about must-haves.
     *
     * <p>"Same broad domain, clearly under-qualified" and "different profession"
     * both fail every must-have, so no rule keyed on must-haves alone can tell
     * a junior backend developer apart from a restaurant manager applying to the
     * same backend job. Coverage across <em>all</em> requirements can: the junior
     * developer still matches nice-to-haves the restaurant manager does not.
     */
    private static Verdict domainFloor(List<RequirementAssessment> assessments) {
        if (assessments.isEmpty()) {
            return Verdict.WEAK_FIT;
        }
        long mustHaves = countOf(assessments, RequirementKind.MUST_HAVE);
        long unsatisfied = countUnsatisfied(assessments);

        if (mustHaves > 0 && unsatisfied == mustHaves) {
            return overallCoverage(assessments) >= DOMAIN_FLOOR ? Verdict.WEAK_FIT : Verdict.NOT_A_FIT;
        }
        if (mustHaves > 0 && unsatisfied * 2 > mustHaves) {
            return Verdict.WEAK_FIT;
        }
        return band(0.8 * coverage(assessments, RequirementKind.MUST_HAVE)
                + 0.2 * coverageOrZero(assessments, RequirementKind.NICE_TO_HAVE));
    }

    /**
     * The band definitions transcribed into code, counting missed must-haves
     * rather than scoring them.
     *
     * <p>"A named gap in a required area" is literally one missed must-have, so
     * that maps to POSSIBLE_FIT directly instead of arriving there through a
     * weighted average that happens to land in the right range. Ratios lose the
     * distinction between one gap out of five and one out of two.
     */
    private static Verdict bandSemantics(List<RequirementAssessment> assessments) {
        return bandSemantics(assessments, 0.0);
    }

    /**
     * As {@link #bandSemantics}, but silence is not treated as equivalent to a
     * contradiction.
     *
     * <p>An UNCLEAR must-have still counts as missed for the band cutoffs — that
     * part of the original design holds, since rewarding silence would beat an
     * honest admission — but it earns partial credit toward the domain signal.
     * A CV that simply does not discuss a requirement is weaker evidence of
     * being the wrong profession than one that shows a different one.
     */
    private static Verdict unclearSoft(List<RequirementAssessment> assessments) {
        return bandSemantics(assessments, 0.25);
    }

    private static Verdict bandSemantics(List<RequirementAssessment> assessments, double unclearCredit) {
        if (assessments.isEmpty()) {
            return Verdict.WEAK_FIT;
        }
        long mustHaves = countOf(assessments, RequirementKind.MUST_HAVE);
        long missed = countUnsatisfied(assessments);

        if (mustHaves == 0) {
            return band(coverageOrZero(assessments, RequirementKind.NICE_TO_HAVE));
        }
        if (missed == 0) {
            // Everything required is met. Nice-to-haves separate "would
            // shortlist" from "clears the bar and brings nothing else".
            return coverageOrZero(assessments, RequirementKind.NICE_TO_HAVE) >= 0.25
                    ? Verdict.STRONG_FIT
                    : Verdict.POSSIBLE_FIT;
        }
        if (missed == 1) {
            return Verdict.POSSIBLE_FIT;
        }
        if (missed < mustHaves) {
            return Verdict.WEAK_FIT;
        }
        return overallCoverage(assessments, unclearCredit) >= DOMAIN_FLOOR
                ? Verdict.WEAK_FIT
                : Verdict.NOT_A_FIT;
    }

    // --- shared -----------------------------------------------------------

    /**
     * Overall coverage below which a candidate is a different profession rather
     * than an under-qualified one.
     *
     * <p>0.15 is low on purpose. It is answering "does this CV match this job in
     * any respect at all", and one solid hit out of a dozen requirements is
     * enough to say yes.
     */
    private static final double DOMAIN_FLOOR = 0.15;

    private static long countOf(List<RequirementAssessment> assessments, RequirementKind kind) {
        return assessments.stream().filter(a -> a.requirementKind() == kind).count();
    }

    private static long countUnsatisfied(List<RequirementAssessment> assessments) {
        return assessments.stream()
                .filter(a -> a.requirementKind() == RequirementKind.MUST_HAVE)
                .filter(VerdictStrategy::isUnsatisfied)
                .count();
    }

    private static double overallCoverage(List<RequirementAssessment> assessments) {
        return overallCoverage(assessments, 0.0);
    }

    /** Credit earned over credit available across every requirement, both kinds. */
    private static double overallCoverage(List<RequirementAssessment> assessments, double unclearCredit) {
        double earned = 0.0;
        for (RequirementAssessment assessment : assessments) {
            earned += switch (assessment.status()) {
                case MET -> 1.0;
                case PARTIAL -> 0.5;
                case UNCLEAR -> unclearCredit;
                case NOT_MET -> 0.0;
            };
        }
        return assessments.isEmpty() ? 0.0 : earned / assessments.size();
    }

    private static boolean isUnsatisfied(RequirementAssessment assessment) {
        return assessment.status() == RequirementStatus.NOT_MET
                || assessment.status() == RequirementStatus.UNCLEAR;
    }

    /**
     * The four-band split of a 0-1 score. The bottom cut at 0.20 is what makes
     * NOT_A_FIT mean "almost nothing matches" rather than "one thing missing".
     */
    private static Verdict band(double score) {
        if (score >= 0.85) {
            return Verdict.STRONG_FIT;
        }
        if (score >= 0.55) {
            return Verdict.POSSIBLE_FIT;
        }
        if (score >= 0.20) {
            return Verdict.WEAK_FIT;
        }
        return Verdict.NOT_A_FIT;
    }

    private static double coverage(List<RequirementAssessment> assessments, RequirementKind kind) {
        double earned = 0.0;
        int available = 0;
        for (RequirementAssessment assessment : assessments) {
            if (assessment.requirementKind() != kind) {
                continue;
            }
            available++;
            if (assessment.status() == RequirementStatus.MET) {
                earned += 1.0;
            } else if (assessment.status() == RequirementStatus.PARTIAL) {
                earned += 0.5;
            }
        }
        return available == 0 ? Double.NaN : earned / available;
    }

    /** As {@link #coverage}, but a job with none of that kind scores 0 rather than NaN. */
    private static double coverageOrZero(List<RequirementAssessment> assessments, RequirementKind kind) {
        double value = coverage(assessments, kind);
        return Double.isNaN(value) ? 0.0 : value;
    }
}
