package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.job.Job;
import com.apliman.cvevaluator.job.JobRequirement;
import com.apliman.cvevaluator.job.RequirementKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The composed prompt, asserted as a string.
 *
 * <p>Cheap to run and it covers the failure this class is most exposed to.
 * The message is assembled from Java text blocks, where indentation is
 * stripped relative to the closing delimiter and a trailing {@code \} joins
 * lines — both easy to get subtly wrong in a way that still compiles, still
 * produces a plausible-looking prompt, and quietly drops a constraint or
 * mangles a heading. Nothing else would catch that: the live test would keep
 * passing, because a model given a slightly damaged prompt still returns
 * well-formed JSON.
 */
class UserMessageTest {

    private static final String CV_TEXT = "Rami Haddad\nNine years on the JVM.";

    private static final List<JobRequirement> REQUIREMENTS = List.of(
            new JobRequirement("R1", "5+ years of Java", RequirementKind.MUST_HAVE),
            new JobRequirement("R2", "Kafka", RequirementKind.NICE_TO_HAVE),
            new JobRequirement("R3", "Kubernetes", RequirementKind.NICE_TO_HAVE));

    @Test
    void carriesEverySectionOfTheStructureInOrder() {
        String message = LlmEvaluator.userMessage(job(), REQUIREMENTS, CV_TEXT);

        assertThat(message)
                .containsSubsequence(
                        "# Role",
                        "# Context",
                        "# Task",
                        "# Constraints",
                        "# Output",
                        "# Examples",
                        "# CV text");
    }

    /**
     * Headings must start at column zero. If the text block's incidental
     * indentation is ever miscounted they arrive indented, which turns them
     * into Markdown code blocks rather than headings.
     */
    @Test
    void headingsAreNotIndented() {
        String message = LlmEvaluator.userMessage(job(), REQUIREMENTS, CV_TEXT);

        assertThat(message.lines().filter(line -> line.stripLeading().startsWith("# ")))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).doesNotStartWith(" "));
    }

    @Test
    void listsEveryRequirementWithItsIdAndKind() {
        String message = LlmEvaluator.userMessage(job(), REQUIREMENTS, CV_TEXT);

        assertThat(message)
                .contains("- R1 [MUST_HAVE] 5+ years of Java")
                .contains("- R2 [NICE_TO_HAVE] Kafka")
                .contains("- R3 [NICE_TO_HAVE] Kubernetes")
                .contains("against each of the 3 requirements");
    }

    /**
     * The rules restated here are exactly the ones {@code validate} rejects a
     * response for. If a constraint is dropped from the prompt the model is
     * being punished for breaking a rule it was never told, so these are pinned
     * individually rather than by counting lines.
     */
    @Test
    void restatesTheRulesThatValidationEnforces() {
        String message = LlmEvaluator.userMessage(job(), REQUIREMENTS, CV_TEXT);

        assertThat(message)
                .contains("exactly one assessment per requirement id")
                .contains("Echo each id verbatim")
                .contains("An absence cannot be quoted")
                .contains("character for character")
                .contains("0-5 inclusive")
                .contains("Do not reward length")
                .contains("Do not output an overall verdict");
    }

    @Test
    void fencesTheCvBetweenExplicitMarkers() {
        String message = LlmEvaluator.userMessage(job(), REQUIREMENTS, CV_TEXT);

        assertThat(message).containsSubsequence("--- BEGIN CV ---", CV_TEXT, "--- END CV ---");
    }

    /** The CV must sit after every instruction, so nothing in it reads as the brief. */
    @Test
    void putsTheCvAfterAllInstructions() {
        String message = LlmEvaluator.userMessage(job(), REQUIREMENTS, CV_TEXT);

        assertThat(message.indexOf(CV_TEXT)).isGreaterThan(message.indexOf("# Constraints"));
    }

    @Test
    void carriesTheJobTitleSeniorityAndDescription() {
        String message = LlmEvaluator.userMessage(job(), REQUIREMENTS, CV_TEXT);

        assertThat(message)
                .contains("Title: Senior Backend Engineer")
                .contains("Seniority: Senior")
                .contains("We are building the transaction core");
    }

    /** Seniority is optional on a Job; a blank one must not leave a dangling label. */
    @Test
    void omitsTheSeniorityLineWhenTheJobHasNone() {
        Job job = new Job("Backend Engineer", "desc", null, REQUIREMENTS, null);

        assertThat(LlmEvaluator.userMessage(job, REQUIREMENTS, CV_TEXT))
                .doesNotContain("Seniority:");
    }

    /**
     * A null description must not put the literal "null" into the prompt, which
     * the model would read as content.
     */
    @Test
    void survivesANullDescriptionAndNullCvText() {
        Job job = new Job("Backend Engineer", null, "Senior", REQUIREMENTS, null);

        assertThat(LlmEvaluator.userMessage(job, REQUIREMENTS, null))
                .doesNotContain("null\n")
                .contains("--- BEGIN CV ---");
    }

    private static Job job() {
        return new Job(
                "Senior Backend Engineer",
                "We are building the transaction core of a regional payments platform.",
                "Senior",
                REQUIREMENTS,
                null);
    }
}
