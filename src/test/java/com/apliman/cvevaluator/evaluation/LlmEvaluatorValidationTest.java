package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.job.JobRequirement;
import com.apliman.cvevaluator.job.RequirementKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The output contract, driven directly against hand-built responses.
 *
 * <h2>Why this is not covered by the integration test</h2>
 *
 * {@code LlmEvaluatorIntegrationTest} proves a real model produces a valid
 * response. It cannot prove an <em>invalid</em> one is rejected, because there
 * is no way to ask Gemini to skip a requirement id or attach a quote to an
 * UNCLEAR on demand. Every rule here is a rule that fires only when something
 * has gone wrong, so the only way to exercise it is to build the broken
 * response by hand.
 *
 * <p>That matters more here than in most places: the JSON schema is appended to
 * the prompt as advisory text rather than enforced by the provider, and the
 * rubric is a request. {@code validate} is the single point where anything is
 * actually guaranteed, so it is the one piece whose failure paths need to be
 * pinned.
 */
class LlmEvaluatorValidationTest {

    private static final List<JobRequirement> REQUIREMENTS = List.of(
            new JobRequirement("R1", "5+ years of Java", RequirementKind.MUST_HAVE),
            new JobRequirement("R2", "Kafka", RequirementKind.NICE_TO_HAVE));

    @Test
    void aWellFormedResponsePasses() {
        assertThatCode(() -> LlmEvaluator.validate(REQUIREMENTS, response()))
                .doesNotThrowAnyException();
    }

    // --- summary ---------------------------------------------------------

    @Test
    void aMissingSummaryIsRejected() {
        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS, responseWithSummary(null)))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("no summary");
    }

    @Test
    void aBlankSummaryIsRejected() {
        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS, responseWithSummary("   \n  ")))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("no summary");
    }

    /**
     * The case the length floor exists for. This is well-formed JSON, a real
     * sentence, and completely useless to a recruiter — nothing structural
     * catches it.
     */
    @Test
    void aSummaryTooShortToBeUsefulIsRejected() {
        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS, responseWithSummary("Good candidate.")))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("too short");
    }

    /** Leading and trailing whitespace must not pad a short summary past the floor. */
    @Test
    void aShortSummaryPaddedWithWhitespaceIsStillRejected() {
        String padded = "          Strong fit.                              ";
        assertThat(padded.length()).isGreaterThan(40);

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS, responseWithSummary(padded)))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void aSummaryOfARealisticLengthPasses() {
        assertThatCode(() -> LlmEvaluator.validate(REQUIREMENTS, responseWithSummary(
                "Strong match on the Java and Spring Boot must-haves, with Kafka described "
                        + "at design level rather than as a keyword.")))
                .doesNotThrowAnyException();
    }

    // --- reasoning -------------------------------------------------------

    @Test
    void aMissingReasoningOnAnAssessmentIsRejected() {
        List<RequirementAssessment> assessments = List.of(
                assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET),
                new RequirementAssessment("R2", "Kafka", RequirementKind.NICE_TO_HAVE,
                        null, RequirementStatus.PARTIAL, "Kafka, Kubernetes"));

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(assessments, dimensionScores(), validSummary())))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("no reasoning for requirement 'R2'");
    }

    @Test
    void aBlankReasoningOnAnAssessmentIsRejected() {
        List<RequirementAssessment> assessments = List.of(
                assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET),
                new RequirementAssessment("R2", "Kafka", RequirementKind.NICE_TO_HAVE,
                        "   ", RequirementStatus.PARTIAL, "Kafka, Kubernetes"));

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(assessments, dimensionScores(), validSummary())))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("no reasoning");
    }

    /**
     * The placeholder case. Well-formed, ordered correctly, and not an argument
     * — nothing else in the pipeline would notice.
     */
    @Test
    void aNonAnswerReasoningIsRejected() {
        List<RequirementAssessment> assessments = List.of(
                assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET),
                new RequirementAssessment("R2", "Kafka", RequirementKind.NICE_TO_HAVE,
                        "N/A", RequirementStatus.PARTIAL, "Kafka, Kubernetes"));

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(assessments, dimensionScores(), validSummary())))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("too short to be an argument");
    }

    /**
     * The floor must not punish a terse but complete answer. "Not mentioned."
     * is the whole truth for an UNCLEAR and has to pass.
     */
    @Test
    void aShortButRealReasoningPasses() {
        List<RequirementAssessment> assessments = List.of(
                assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET),
                new RequirementAssessment("R2", "Kafka", RequirementKind.NICE_TO_HAVE,
                        "Not mentioned.", RequirementStatus.UNCLEAR, null));

        assertThatCode(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(assessments, dimensionScores(), validSummary())))
                .doesNotThrowAnyException();
    }

    @Test
    void aMissingReasoningOnADimensionScoreIsRejected() {
        List<DimensionScore> scores = List.of(
                new DimensionScore(ScoreDimension.IMPACT_AND_OWNERSHIP, null, 4, null),
                new DimensionScore(ScoreDimension.COMMUNICATION_QUALITY, "Specific throughout.", 5, null));

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(response().requirementAssessments(), scores, validSummary())))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("IMPACT_AND_OWNERSHIP");
    }

    @Test
    void aNonAnswerReasoningOnADimensionScoreIsRejected() {
        List<DimensionScore> scores = List.of(
                new DimensionScore(ScoreDimension.IMPACT_AND_OWNERSHIP, "Good", 4, null),
                new DimensionScore(ScoreDimension.COMMUNICATION_QUALITY, "Specific throughout.", 5, null));

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(response().requirementAssessments(), scores, validSummary())))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("too short to be an argument");
    }

    // --- requirement ids -------------------------------------------------

    @Test
    void aSkippedRequirementIsRejected() {
        LlmEvaluationResponse body = new LlmEvaluationResponse(
                List.of(assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET)),
                dimensionScores(), validSummary());

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS, body))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("skipped")
                .hasMessageContaining("R2");
    }

    @Test
    void anInventedRequirementIdIsRejected() {
        List<RequirementAssessment> assessments = new ArrayList<>(response().requirementAssessments());
        assessments.add(assessment("R9", RequirementKind.NICE_TO_HAVE, RequirementStatus.MET));

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(assessments, dimensionScores(), validSummary())))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("R9");
    }

    @Test
    void aDuplicatedRequirementIdIsRejected() {
        List<RequirementAssessment> assessments = List.of(
                assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET),
                assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.PARTIAL));

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(assessments, dimensionScores(), validSummary())))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("more than once");
    }

    // --- the null-quote rule ---------------------------------------------

    @Test
    void aQuoteOnAnUnclearAssessmentIsRejected() {
        List<RequirementAssessment> assessments = List.of(
                assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET),
                new RequirementAssessment("R2", "Kafka", RequirementKind.NICE_TO_HAVE,
                        "The CV never mentions Kafka.", RequirementStatus.UNCLEAR,
                        "Backend engineer with nine years"));

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(assessments, dimensionScores(), validSummary())))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("absence cannot be quoted");
    }

    @Test
    void aQuoteOnANotMetAssessmentIsRejected() {
        List<RequirementAssessment> assessments = List.of(
                assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET),
                new RequirementAssessment("R2", "Kafka", RequirementKind.NICE_TO_HAVE,
                        "The CV describes RabbitMQ throughout.", RequirementStatus.NOT_MET,
                        "Used RabbitMQ for all messaging"));

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(assessments, dimensionScores(), validSummary())))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("absence cannot be quoted");
    }

    // --- dimension scores ------------------------------------------------

    @Test
    void aScoreOutsideZeroToFiveIsRejected() {
        List<DimensionScore> scores = List.of(
                new DimensionScore(ScoreDimension.IMPACT_AND_OWNERSHIP, "Owns outcomes.", 7, null),
                new DimensionScore(ScoreDimension.COMMUNICATION_QUALITY, "Specific throughout.", 4, null));

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(response().requirementAssessments(), scores, validSummary())))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("outside 0-5");
    }

    @Test
    void aMissingDimensionIsRejected() {
        List<DimensionScore> scores = List.of(
                new DimensionScore(ScoreDimension.IMPACT_AND_OWNERSHIP, "Owns outcomes.", 4, null));

        assertThatThrownBy(() -> LlmEvaluator.validate(REQUIREMENTS,
                new LlmEvaluationResponse(response().requirementAssessments(), scores, validSummary())))
                .isInstanceOf(EvaluationParseException.class)
                .hasMessageContaining("COMMUNICATION_QUALITY");
    }

    // --- fixtures --------------------------------------------------------

    private static LlmEvaluationResponse response() {
        return responseWithSummary(validSummary());
    }

    private static LlmEvaluationResponse responseWithSummary(String summary) {
        return new LlmEvaluationResponse(
                List.of(
                        assessment("R1", RequirementKind.MUST_HAVE, RequirementStatus.MET),
                        assessment("R2", RequirementKind.NICE_TO_HAVE, RequirementStatus.PARTIAL)),
                dimensionScores(),
                summary);
    }

    private static List<DimensionScore> dimensionScores() {
        return List.of(
                new DimensionScore(ScoreDimension.IMPACT_AND_OWNERSHIP, "Owns outcomes.", 4, null),
                new DimensionScore(ScoreDimension.COMMUNICATION_QUALITY, "Specific throughout.", 5, null));
    }

    private static String validSummary() {
        return "A realistic summary sentence that comfortably clears the minimum length floor.";
    }

    private static RequirementAssessment assessment(
            String id, RequirementKind kind, RequirementStatus status) {
        boolean absence = status == RequirementStatus.NOT_MET || status == RequirementStatus.UNCLEAR;
        return new RequirementAssessment(id, "Requirement " + id, kind,
                "Reasoning for " + id, status, absence ? null : "quote for " + id);
    }
}
