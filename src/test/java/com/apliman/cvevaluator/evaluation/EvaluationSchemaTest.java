package com.apliman.cvevaluator.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shape of the JSON schema the model is actually shown.
 *
 * <h2>Why this test exists</h2>
 *
 * The reasoning-before-verdict design rests on one fact: the model fills the
 * response object in the order the schema lists the fields, so
 * {@code reasoning} must appear before {@code status}. That order is
 * <em>not</em> a free consequence of how the records are declared. Spring AI's
 * schema generator sorts properties alphabetically, which on
 * {@link RequirementAssessment} puts {@code evidenceQuote} first and
 * {@code status} before nothing at all — quote chosen first, verdict last,
 * reasoning stranded in the middle. {@code @JsonPropertyOrder} is what corrects
 * it.
 *
 * <p>So the invariant is enforced by an annotation that is easy to drop in a
 * refactor, and breaking it changes nothing visible: the code still compiles,
 * every other test still passes, and the evaluations quietly get worse in a way
 * no assertion would catch. That is exactly the kind of thing that needs a test
 * pointed at it.
 *
 * <p>No Spring context and no API key — this reads the schema the converter
 * generates, which is the same string that gets appended to the prompt.
 */
class EvaluationSchemaTest {

    private static final JsonNode SCHEMA = JsonMapper.builder().build()
            .readTree(new BeanOutputConverter<>(LlmEvaluationResponse.class).getJsonSchema());

    @Test
    void requirementAssessment_putsReasoningBeforeStatusAndStatusBeforeTheQuote() {
        List<String> order = propertyOrder(itemsOf("requirementAssessments"));

        assertThat(order).containsExactly(
                "requirementId", "requirementText", "requirementKind",
                "reasoning", "status", "evidenceQuote");
    }

    @Test
    void dimensionScore_putsReasoningBeforeTheScore() {
        List<String> order = propertyOrder(itemsOf("dimensionScores"));

        assertThat(order).containsExactly("dimension", "reasoning", "score", "evidenceQuote");
    }

    @Test
    void topLevel_putsTheAssessmentsBeforeTheSummary() {
        assertThat(propertyOrder(SCHEMA))
                .containsExactly("requirementAssessments", "dimensionScores", "summary");
    }

    /**
     * The quote must not be in {@code required}. Every other field is, and a
     * schema that demands a quote while the rubric forbids one on NOT_MET and
     * UNCLEAR leaves the model to resolve the contradiction — which it does by
     * inventing a quote, the single failure this system exists to prevent.
     */
    @Test
    void evidenceQuote_isNotRequired_soAnAbsenceCanBeReportedAsNull() {
        assertThat(requiredOf(itemsOf("requirementAssessments")))
                .contains("requirementId", "reasoning", "status")
                .doesNotContain("evidenceQuote");

        assertThat(requiredOf(itemsOf("dimensionScores")))
                .contains("dimension", "reasoning", "score")
                .doesNotContain("evidenceQuote");
    }

    /** The enum constants are named in the schema, so the model cannot guess them. */
    @Test
    void statusAndDimensionAreConstrainedToTheirEnumConstants() {
        assertThat(itemsOf("requirementAssessments").path("properties").path("status").path("enum"))
                .map(JsonNode::asString)
                .containsExactlyInAnyOrder("MET", "PARTIAL", "NOT_MET", "UNCLEAR");

        assertThat(itemsOf("dimensionScores").path("properties").path("dimension").path("enum"))
                .map(JsonNode::asString)
                .containsExactlyInAnyOrder("IMPACT_AND_OWNERSHIP", "COMMUNICATION_QUALITY");
    }

    private static JsonNode itemsOf(String arrayProperty) {
        return SCHEMA.path("properties").path(arrayProperty).path("items");
    }

    /**
     * Key order as the JSON text carries it — Jackson's object nodes preserve
     * insertion order, which is what makes this readable as "what the model sees
     * first".
     */
    private static List<String> propertyOrder(JsonNode objectSchema) {
        List<String> names = new ArrayList<>();
        objectSchema.path("properties").propertyNames().forEach(names::add);
        return names;
    }

    private static List<String> requiredOf(JsonNode objectSchema) {
        List<String> names = new ArrayList<>();
        objectSchema.path("required").forEach(node -> names.add(node.asString()));
        return names;
    }
}
