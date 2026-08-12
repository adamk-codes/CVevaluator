package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.job.RequirementKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verdict rules, pinned.
 *
 * <p>No Spring context, no mocks, no model. That is the point of
 * {@link VerdictCalculator} being pure Java: the rule that decides what a
 * recruiter sees is testable in microseconds and readable as a table. If any of
 * these ever needs an API key to run, the calculator has grown a dependency it
 * should not have.
 */
class VerdictCalculatorTest {

    private final VerdictCalculator calculator = new VerdictCalculator();

    @Test
    void everyRequirementMet_isStrongFit() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.addAll(mustHaves(3, RequirementStatus.MET));
        assessments.addAll(niceToHaves(4, RequirementStatus.MET));

        assertThat(calculator.calculate(assessments)).isEqualTo(Verdict.STRONG_FIT);
    }

    /**
     * The case that broke the previous rule, and the reason this class changed.
     *
     * <p>One missed must-have is the manifest's definition of a borderline
     * candidate — "real overlap but a named gap in a required area". The old
     * gate answered NOT_A_FIT here, which made POSSIBLE_FIT unreachable for any
     * realistic CV and collapsed a four-band scale to two.
     */
    @Test
    void exactlyOneMustHaveMissed_isPossibleFit() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.add(assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET));
        assessments.add(assessment("R2", RequirementKind.MUST_HAVE, RequirementStatus.NOT_MET));
        assessments.addAll(niceToHaves(6, RequirementStatus.MET));

        assertThat(calculator.calculate(assessments)).isEqualTo(Verdict.POSSIBLE_FIT);
    }

    /**
     * UNCLEAR still counts as missed, and that has not changed. The two are
     * different findings but neither is evidence the requirement is satisfied,
     * and letting silence pass would score a CV that omits a topic above one
     * that admits the gap. Measured, too: giving UNCLEAR credit collapsed
     * NOT_A_FIT accuracy from 34/34 to 18/34 on the corpus.
     */
    @Test
    void aMustHaveThatIsUnclearCountsAsMissedJustLikeNotMet() {
        List<RequirementAssessment> withUnclear = new ArrayList<>();
        withUnclear.add(assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET));
        withUnclear.add(assessment("R2", RequirementKind.MUST_HAVE, RequirementStatus.UNCLEAR));
        withUnclear.addAll(niceToHaves(6, RequirementStatus.MET));

        List<RequirementAssessment> withNotMet = new ArrayList<>();
        withNotMet.add(assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET));
        withNotMet.add(assessment("R2", RequirementKind.MUST_HAVE, RequirementStatus.NOT_MET));
        withNotMet.addAll(niceToHaves(6, RequirementStatus.MET));

        assertThat(calculator.calculate(withUnclear))
                .isEqualTo(calculator.calculate(withNotMet))
                .isEqualTo(Verdict.POSSIBLE_FIT);
    }

    /** More than one gap, but not every one: under-qualified rather than unqualified. */
    @Test
    void severalMustHavesMissedButNotAll_isWeakFit() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.add(assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET));
        assessments.add(assessment("R2", RequirementKind.MUST_HAVE, RequirementStatus.NOT_MET));
        assessments.add(assessment("R3", RequirementKind.MUST_HAVE, RequirementStatus.UNCLEAR));
        assessments.addAll(niceToHaves(4, RequirementStatus.MET));

        assertThat(calculator.calculate(assessments)).isEqualTo(Verdict.WEAK_FIT);
    }

    /**
     * Every must-have missed, but the CV clearly relates to the job: the junior
     * developer case. NOT_A_FIT means a different profession, so this has to
     * stay WEAK_FIT — no rule keyed on must-haves alone can tell these two
     * apart, which is exactly what DOMAIN_FLOOR exists for.
     */
    @Test
    void everyMustHaveMissedButNiceToHavesMatched_isWeakFitNotNotAFit() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.addAll(mustHaves(3, RequirementStatus.NOT_MET));
        assessments.addAll(niceToHaves(5, RequirementStatus.MET));

        assertThat(calculator.calculate(assessments)).isEqualTo(Verdict.WEAK_FIT);
    }

    /** Nothing matches anywhere. The only route to NOT_A_FIT. */
    @Test
    void nothingMatchesAtAll_isNotAFit() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.addAll(mustHaves(3, RequirementStatus.NOT_MET));
        assessments.addAll(niceToHaves(6, RequirementStatus.UNCLEAR));

        assertThat(calculator.calculate(assessments)).isEqualTo(Verdict.NOT_A_FIT);
    }

    /**
     * Half credit on every line still leaves zero must-haves <em>missed</em>,
     * since PARTIAL is neither NOT_MET nor UNCLEAR. Nice-to-have coverage of
     * 0.5 clears the strong-fit floor, so this is a STRONG_FIT under the new
     * rule where the old score-based one called it WEAK_FIT.
     */
    @Test
    void everyRequirementPartial_isStrongFit() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.addAll(mustHaves(3, RequirementStatus.PARTIAL));
        assessments.addAll(niceToHaves(5, RequirementStatus.PARTIAL));

        assertThat(calculator.calculate(assessments)).isEqualTo(Verdict.STRONG_FIT);
    }

    /**
     * Clears every must-have and brings nothing else. Worth a conversation, not
     * a headline — the one case that separates STRONG_FIT from POSSIBLE_FIT
     * when no requirement was missed at all.
     */
    @Test
    void allMustHavesMetAndNoNiceToHaves_isPossibleFit() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.addAll(mustHaves(4, RequirementStatus.MET));
        assessments.addAll(niceToHaves(7, RequirementStatus.UNCLEAR));

        assertThat(calculator.calculate(assessments)).isEqualTo(Verdict.POSSIBLE_FIT);
    }

    /** All four verdicts must be reachable. The previous rule could produce two. */
    @Test
    void everyVerdictIsReachable() {
        assertThat(List.of(
                calculator.calculate(strongFitCase()),
                calculator.calculate(possibleFitCase()),
                calculator.calculate(weakFitCase()),
                calculator.calculate(notAFitCase())))
                .containsExactly(Verdict.STRONG_FIT, Verdict.POSSIBLE_FIT,
                        Verdict.WEAK_FIT, Verdict.NOT_A_FIT);
    }

    private static List<RequirementAssessment> strongFitCase() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.addAll(mustHaves(3, RequirementStatus.MET));
        assessments.addAll(niceToHaves(4, RequirementStatus.MET));
        return assessments;
    }

    private static List<RequirementAssessment> possibleFitCase() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.add(assessment("M1", RequirementKind.MUST_HAVE, RequirementStatus.MET));
        assessments.add(assessment("M2", RequirementKind.MUST_HAVE, RequirementStatus.NOT_MET));
        assessments.addAll(niceToHaves(4, RequirementStatus.MET));
        return assessments;
    }

    private static List<RequirementAssessment> weakFitCase() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.addAll(mustHaves(3, RequirementStatus.NOT_MET));
        assessments.addAll(niceToHaves(5, RequirementStatus.MET));
        return assessments;
    }

    private static List<RequirementAssessment> notAFitCase() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.addAll(mustHaves(3, RequirementStatus.NOT_MET));
        assessments.addAll(niceToHaves(6, RequirementStatus.NOT_MET));
        return assessments;
    }

    /**
     * A job whose requirements are all MUST_HAVE. Every one met and no
     * nice-to-have list to judge against, so it lands at POSSIBLE_FIT — the
     * same place a candidate who clears every bar and brings nothing extra
     * lands, which is the honest answer when there is nothing extra to bring.
     */
    @Test
    void jobWithNoNiceToHavesAtAll_isPossibleFitWhenEveryMustHaveIsMet() {
        assertThat(calculator.calculate(mustHaves(3, RequirementStatus.MET)))
                .isEqualTo(Verdict.POSSIBLE_FIT);
    }

    /**
     * Unreachable through the API — the validator refuses a job with no
     * requirements — so this pins the defensive branch. WEAK_FIT, not
     * NOT_A_FIT: nothing failed, there was simply nothing to assess, and
     * NOT_A_FIT should only ever follow a named missing must-have.
     */
    @Test
    void emptyAssessmentList_isWeakFit() {
        assertThat(calculator.calculate(List.of())).isEqualTo(Verdict.WEAK_FIT);
        assertThat(calculator.calculate(null)).isEqualTo(Verdict.WEAK_FIT);
    }

    private static List<RequirementAssessment> mustHaves(int count, RequirementStatus status) {
        return run(count, "M", RequirementKind.MUST_HAVE, status);
    }

    private static List<RequirementAssessment> niceToHaves(int count, RequirementStatus status) {
        return run(count, "N", RequirementKind.NICE_TO_HAVE, status);
    }

    private static List<RequirementAssessment> run(
            int count, String prefix, RequirementKind kind, RequirementStatus status) {
        List<RequirementAssessment> assessments = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            assessments.add(assessment(prefix + i, kind, status));
        }
        return assessments;
    }

    /**
     * Only kind and status are read by the calculator; the rest is filled in so
     * the fixtures look like real assessments rather than like the three fields
     * that happen to matter today.
     */
    private static RequirementAssessment assessment(
            String id, RequirementKind kind, RequirementStatus status) {
        boolean absence = status == RequirementStatus.NOT_MET || status == RequirementStatus.UNCLEAR;
        return new RequirementAssessment(
                id,
                "Requirement " + id,
                kind,
                "Reasoning for " + id,
                status,
                absence ? null : "quote for " + id);
    }
}
