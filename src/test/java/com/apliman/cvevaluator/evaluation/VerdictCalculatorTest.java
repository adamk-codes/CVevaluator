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
     * The hard gate. Everything else being perfect must not rescue it — that is
     * what MUST_HAVE means, and if a strong CV can slip through a failed
     * must-have the recruiter's answer to "is a candidate without this out?" was
     * never actually honoured.
     */
    @Test
    void oneMustHaveNotMet_isNotAFit_evenWhenEverythingElseIsMet() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.add(assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET));
        assessments.add(assessment("R2", RequirementKind.MUST_HAVE, RequirementStatus.NOT_MET));
        assessments.addAll(niceToHaves(6, RequirementStatus.MET));

        assertThat(calculator.calculate(assessments)).isEqualTo(Verdict.NOT_A_FIT);
    }

    /**
     * UNCLEAR on a must-have is the same outcome as NOT_MET and for a reason
     * worth stating out loud: the two are different findings, but neither is
     * evidence that the requirement is satisfied. Letting silence pass would
     * score a CV that omits the topic above one that admits the gap.
     */
    @Test
    void oneMustHaveUnclear_isNotAFit_evenWhenEverythingElseIsMet() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.add(assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET));
        assessments.add(assessment("R2", RequirementKind.MUST_HAVE, RequirementStatus.UNCLEAR));
        assessments.addAll(niceToHaves(6, RequirementStatus.MET));

        assertThat(calculator.calculate(assessments)).isEqualTo(Verdict.NOT_A_FIT);
    }

    /**
     * Half credit everywhere lands at 0.5, just under the 0.55 possible-fit
     * threshold. This is the test that pins the gap between those two numbers:
     * "related but weaker on every single line" is a weak fit.
     */
    @Test
    void everyRequirementPartial_isWeakFit() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.addAll(mustHaves(3, RequirementStatus.PARTIAL));
        assessments.addAll(niceToHaves(5, RequirementStatus.PARTIAL));

        assertThat(calculator.calculate(assessments)).isEqualTo(Verdict.WEAK_FIT);
    }

    /**
     * Clears every gate, brings nothing else: 0.8 * 1.0 + 0.2 * 0.0 = 0.80,
     * which is below STRONG_FIT_THRESHOLD on purpose. This case is the whole
     * reason that threshold is 0.85 and not 0.80 — a candidate who is exactly
     * qualified and no more is worth a conversation, not a headline.
     */
    @Test
    void allMustHavesMetAndNoNiceToHaves_isPossibleFit() {
        List<RequirementAssessment> assessments = new ArrayList<>();
        assessments.addAll(mustHaves(4, RequirementStatus.MET));
        assessments.addAll(niceToHaves(7, RequirementStatus.UNCLEAR));

        assertThat(calculator.calculate(assessments)).isEqualTo(Verdict.POSSIBLE_FIT);
    }

    /**
     * A job whose requirements are all MUST_HAVE must still be able to reach
     * STRONG_FIT. Scoring an absent nice-to-have list as 0.0 coverage would cap
     * it at 0.80 forever, and nothing about the output would show why.
     */
    @Test
    void jobWithNoNiceToHavesAtAll_canStillReachStrongFit() {
        assertThat(calculator.calculate(mustHaves(3, RequirementStatus.MET)))
                .isEqualTo(Verdict.STRONG_FIT);
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
