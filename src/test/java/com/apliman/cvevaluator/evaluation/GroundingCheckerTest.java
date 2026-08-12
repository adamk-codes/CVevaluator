package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.job.RequirementKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check that a quote is real.
 *
 * <p>Every case here is a claim about what counts as "copied from the CV", and
 * the interesting ones are the near-misses: text that looks quoted, reads as
 * quoted, and is not.
 */
class GroundingCheckerTest {

    private static final String CV = """
            Rami Haddad
            Backend engineer with nine years building transactional services on the JVM.

            EXPERIENCE
            Principal Backend Engineer, Beirut Payments
            - Cut p99 latency on the balance endpoint from 840ms to 110ms by replacing
              a correlated subquery with a lateral join.
            - Run the service in production myself, including the on-call rotation.

            TECHNICAL SKILLS
            Java, Spring Boot, PostgreSQL, Kafka, Kubernetes
            """;

    private final GroundingChecker checker = new GroundingChecker();

    @Test
    void aQuoteCopiedExactlyIsGrounded() {
        assertThat(checker.check(List.of(
                quoting("R1", "Run the service in production myself, including the on-call rotation.")), CV))
                .isEmpty();
    }

    /**
     * The case whitespace normalisation exists for. Extraction breaks a wrapped
     * bullet across lines; a model copying it back joins it with one space.
     * That is verbatim in every sense a reader cares about.
     */
    @Test
    void aQuoteRejoinedAcrossAWrappedLineIsGrounded() {
        assertThat(checker.check(List.of(
                quoting("R1", "Cut p99 latency on the balance endpoint from 840ms to 110ms by "
                        + "replacing a correlated subquery with a lateral join.")), CV))
                .isEmpty();
    }

    /**
     * The failure found on the real corpus: two non-adjacent lines welded
     * together. Both halves are genuinely in the CV, which is what makes it
     * dangerous — it reads as a quote and is not one.
     */
    @Test
    void aQuoteMergingTwoNonAdjacentLinesIsNotGrounded() {
        List<GroundingChecker.Ungrounded> ungrounded = checker.check(List.of(
                quoting("R1", "Principal Backend Engineer, Beirut Payments\nJava, Spring Boot, "
                        + "PostgreSQL, Kafka, Kubernetes")), CV);

        assertThat(ungrounded).singleElement()
                .extracting(GroundingChecker.Ungrounded::requirementId)
                .isEqualTo("R1");
    }

    @Test
    void anInventedQuoteIsNotGrounded() {
        assertThat(checker.check(List.of(
                quoting("R1", "Led a team of twelve engineers across three countries.")), CV))
                .hasSize(1);
    }

    /**
     * A paraphrase is a fabrication for this purpose. "Close enough to what the
     * candidate wrote" is not a claim this project makes, and forgiving it here
     * would also forgive a rewrite that changes the meaning.
     */
    @Test
    void aParaphraseIsNotGrounded() {
        assertThat(checker.check(List.of(
                quoting("R1", "Backend engineer with 9 years building transactional services on the JVM.")), CV))
                .hasSize(1);
    }

    /** Case is content. "JAVA" is not what the CV says. */
    @Test
    void aQuoteWithChangedCaseIsNotGrounded() {
        assertThat(checker.check(List.of(
                quoting("R1", "JAVA, SPRING BOOT, POSTGRESQL")), CV))
                .hasSize(1);
    }

    @Test
    void assessmentsWithNoQuoteAreIgnored() {
        List<RequirementAssessment> assessments = List.of(
                new RequirementAssessment("R1", "Kafka", RequirementKind.NICE_TO_HAVE,
                        "Never mentioned.", RequirementStatus.UNCLEAR, null),
                new RequirementAssessment("R2", "Go", RequirementKind.NICE_TO_HAVE,
                        "Never mentioned.", RequirementStatus.NOT_MET, "   "));

        assertThat(checker.check(assessments, CV)).isEmpty();
    }

    @Test
    void everyUngroundedQuoteIsReportedNotJustTheFirst() {
        List<GroundingChecker.Ungrounded> ungrounded = checker.check(List.of(
                quoting("R1", "Invented one."),
                quoting("R2", "Run the service in production myself, including the on-call rotation."),
                quoting("R3", "Invented two.")), CV);

        assertThat(ungrounded)
                .extracting(GroundingChecker.Ungrounded::requirementId)
                .containsExactly("R1", "R3");
    }

    /**
     * The redaction trap, pinned. If the model is given redacted text and the
     * check is run against the original, every quote touching a redacted span
     * fails — the checker reports fabrication on a perfect copy. This asserts
     * the mechanism so the javadoc warning on {@code evaluate} is not the only
     * thing standing between a caller and a very confusing bug report.
     */
    @Test
    void checkingAgainstDifferentTextThanTheModelSawReportsFalseFabrication() {
        String redacted = "Contact: [EMAIL] | [PHONE]";
        String original = "Contact: rami@example.com | +961 3 123456";

        assertThat(checker.check(List.of(quoting("R1", "Contact: [EMAIL] | [PHONE]")), redacted))
                .as("grounded against the text the model actually saw")
                .isEmpty();

        assertThat(checker.check(List.of(quoting("R1", "Contact: [EMAIL] | [PHONE]")), original))
                .as("same quote, checked against the wrong text, now looks fabricated")
                .hasSize(1);
    }

    @Test
    void emptyAndNullInputsAreHandled() {
        assertThat(checker.check(List.of(), CV)).isEmpty();
        assertThat(checker.check(null, CV)).isEmpty();
        assertThat(checker.check(List.of(quoting("R1", "anything")), null)).hasSize(1);
    }

    private static RequirementAssessment quoting(String id, String quote) {
        return new RequirementAssessment(id, "Requirement " + id, RequirementKind.MUST_HAVE,
                "Reasoning for " + id, RequirementStatus.MET, quote);
    }
}
