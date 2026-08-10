package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.extraction.CvTextExtractor;
import com.apliman.cvevaluator.job.Job;
import com.apliman.cvevaluator.job.JobRequirement;
import com.apliman.cvevaluator.job.dto.CreateJobRequest;
import com.apliman.cvevaluator.redaction.PiiRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.chat.observation.autoconfigure.ChatObservationAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real thing: fixture CV 1 against fixture job 1, through the real model.
 *
 * <h2>Why it is gated on the key and not on a profile</h2>
 *
 * {@code @EnabledIfEnvironmentVariable} means a fresh clone with no key runs
 * {@code mvn test} and sees this skipped, not failed. A profile would need
 * someone to know to set it; the key is the thing that is actually missing, so
 * it is the thing that decides.
 *
 * <h2>Why an ApplicationContextRunner and not @SpringBootTest</h2>
 *
 * {@code @SpringBootTest} would start the whole application, which needs
 * PostgreSQL up. This test has nothing to do with the database, and a test that
 * needs both a running database and a paid API key is a test that gets disabled.
 * The runner brings up only the five autoconfigurations the evaluator needs.
 *
 * <h2>What it costs</h2>
 *
 * One Gemini call per run, on an eleven-requirement job and a ~2,700 character
 * CV. It is deliberately a single test method rather than several sharing a
 * cached result, because a shared result would hide a non-deterministic
 * failure behind whichever assertion happened to run first.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class LlmEvaluatorIntegrationTest {

    private static final Path FIXTURES = Path.of("fixtures");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SpringAiRetryAutoConfiguration.class,
                    ToolCallingAutoConfiguration.class,
                    ChatObservationAutoConfiguration.class,
                    GoogleGenAiChatAutoConfiguration.class,
                    ChatClientAutoConfiguration.class))
            .withUserConfiguration(EvaluationBeans.class)
            // Only the api-key. No project-id, no location - either one flips
            // the client into Vertex AI mode and rejects a Developer key.
            .withPropertyValues(
                    "spring.ai.google.genai.api-key=" + System.getenv("GEMINI_API_KEY"),
                    "spring.ai.google.genai.chat.options.model=gemini-2.5-flash");

    @Configuration(proxyBeanMethods = false)
    @Import({AssessmentRubric.class, VerdictCalculator.class, LlmEvaluator.class})
    static class EvaluationBeans {
    }

    @Test
    void fixtureCvAgainstFixtureJob_producesAnEvaluationThatHonoursTheContract() {
        contextRunner.run(context -> {
            Job job = fixtureJob();
            String cvText = fixtureCvText();

            EvaluationResult result = context.getBean(LlmEvaluator.class).evaluate(job, cvText);

            // 1. Exactly one assessment per requirement, ids matching the job's.
            List<String> expectedIds = job.getRequirements().stream().map(JobRequirement::id).toList();
            assertThat(result.assessments())
                    .as("one assessment per authored requirement, in the job's order")
                    .extracting(RequirementAssessment::requirementId)
                    .containsExactlyElementsOf(expectedIds);

            // 2 and 3. The grounding contract, per status.
            Set<String> uniqueIds = new HashSet<>();
            for (RequirementAssessment assessment : result.assessments()) {
                assertThat(uniqueIds.add(assessment.requirementId()))
                        .as("requirement %s assessed only once", assessment.requirementId())
                        .isTrue();
                assertThat(assessment.reasoning())
                        .as("reasoning for %s", assessment.requirementId())
                        .isNotBlank();

                switch (assessment.status()) {
                    case MET -> {
                        assertThat(assessment.evidenceQuote())
                                .as("MET requirement %s must cite evidence", assessment.requirementId())
                                .isNotBlank();
                        assertThat(normalised(cvText))
                                .as("quote for %s must be verbatim from the CV, not paraphrased: <%s>",
                                        assessment.requirementId(), assessment.evidenceQuote())
                                .contains(normalised(assessment.evidenceQuote()));
                    }
                    // An absence cannot be quoted.
                    case NOT_MET, UNCLEAR -> assertThat(assessment.evidenceQuote())
                            .as("%s requirement %s must have a null quote",
                                    assessment.status(), assessment.requirementId())
                            .isNull();
                    // PARTIAL is unconstrained here on purpose: the evaluator
                    // does not require a quote for it, so asserting one would
                    // pin behaviour the contract does not claim.
                    case PARTIAL -> {
                    }
                }
            }

            // 4. Both dimensions scored, in range.
            assertThat(result.dimensionScores())
                    .extracting(DimensionScore::dimension)
                    .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ScoreDimension.class));
            assertThat(result.dimensionScores())
                    .allSatisfy(score -> {
                        assertThat(score.score()).isBetween(0, 5);
                        assertThat(score.reasoning()).isNotBlank();
                    });

            // 5. A verdict exists and was computed, not generated - the same
            // assessments run back through the calculator must agree.
            assertThat(result.verdict()).isNotNull();
            assertThat(result.verdict())
                    .isEqualTo(new VerdictCalculator().calculate(result.assessments()));

            // 6. Stamped with the job's live version, not a default. The fixture
            // job is deliberately at version 2 so that a hard-coded 1 fails.
            assertThat(result.evaluatedAgainstRequirementsVersion())
                    .isEqualTo(job.getRequirementsVersion())
                    .isEqualTo(2);
            assertThat(result.promptVersion()).isEqualTo(AssessmentRubric.ASSESSMENT_PROMPT_VERSION);

            assertThat(result.summary()).isNotBlank();
            assertThat(result.tokensIn()).isPositive();
            assertThat(result.tokensOut()).isPositive();
            assertThat(result.latencyMs()).isPositive();
        });
    }

    /**
     * Job 1 read from the fixture file the API itself accepts, so the test
     * cannot drift from the corpus by transcription.
     *
     * <p>Requirements are then replaced with the identical list purely to move
     * the version off its initial 1 — assertion 6 is worthless against a value
     * that happens to match the default.
     */
    private static Job fixtureJob() throws IOException {
        CreateJobRequest fixture = tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue(FIXTURES.resolve("jobs/job-01-backend-engineer.json").toFile(),
                        CreateJobRequest.class);

        Job job = new Job(fixture.title(), fixture.description(), fixture.seniority(),
                fixture.requirements(), null);
        job.replaceRequirements(fixture.requirements());
        return job;
    }

    /**
     * The CV as production would supply it: extracted from the real PDF, then
     * redacted. Not the plain-text source in {@code fixtures/content} — that
     * file carries generator markup ({@code >}, {@code -}, {@code ##}) that
     * never reaches the model, and a quote check against it would be checking
     * the wrong string.
     *
     * <p>Redacted, because that is what callers must pass and what the D3
     * grounding checker will match against.
     */
    private static String fixtureCvText() throws IOException {
        String extracted = new CvTextExtractor()
                .extract(FIXTURES.resolve("cvs/cv-01-rami-haddad.pdf"), "pdf")
                .text();
        return new PiiRedactor().redact(extracted);
    }

    /**
     * Collapses whitespace runs before the containment check.
     *
     * <p>This is a tolerance, and worth being explicit about: PDF extraction
     * breaks a wrapped bullet across lines, and a model copying that span back
     * will usually join it with a single space. Requiring a byte-identical match
     * would fail on quotes that are verbatim in every sense a reader cares
     * about. It is the same tolerance the D3 grounding checker needs, which is
     * why it lives here rather than being avoided by loosening the assertion to
     * "contains some words".
     */
    private static String normalised(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
