package com.apliman.cvevaluator.evaluation.harness;

import com.apliman.cvevaluator.evaluation.AssessmentRubric;
import com.apliman.cvevaluator.evaluation.EvaluationResult;
import com.apliman.cvevaluator.evaluation.LlmEvaluator;
import com.apliman.cvevaluator.evaluation.RequirementAssessment;
import com.apliman.cvevaluator.evaluation.RequirementStatus;
import com.apliman.cvevaluator.evaluation.VerdictCalculator;
import com.apliman.cvevaluator.extraction.CvTextExtractor;
import com.apliman.cvevaluator.job.Job;
import com.apliman.cvevaluator.job.dto.CreateJobRequest;
import com.apliman.cvevaluator.redaction.PiiRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.chat.observation.autoconfigure.ChatObservationAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scores the whole fixture corpus against the answer key in
 * {@code fixtures/manifest.json}.
 *
 * <h2>What this is for</h2>
 *
 * Everything we know about grading quality today comes from one CV against one
 * job. That proves the pipeline runs; it says nothing about whether the grading
 * is any good, and — the reason this exists — nothing about whether a change
 * made it better or worse. This turns "the batched version feels sharper" into
 * a number that can be compared, defended, and disagreed with.
 *
 * <h2>It measures, it does not gate</h2>
 *
 * Deliberately asserts almost nothing. A quality threshold here would either be
 * set so low it never fires or would turn an experiment into a broken build the
 * first time a prompt change traded a point of agreement for something else.
 * The only assertion is that the run produced results at all, which catches the
 * harness itself being broken rather than the grading being poor.
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * mvnw test -Dtest=EvaluationHarness -Dcvevaluator.harness=true
 * </pre>
 *
 * Two gates, both required: {@code GEMINI_API_KEY} present, and the opt-in
 * property. The property exists because this makes 60 paid calls and takes
 * roughly 20 minutes — it must never fire from a plain {@code mvn test}.
 *
 * <p>There is a third layer, and it is worth knowing about because it explains
 * why {@code mvn test} shows no trace of this class at all — not even as
 * skipped. Surefire's default includes are {@code Test*}, {@code *Test},
 * {@code *Tests} and {@code *TestCase}; {@code EvaluationHarness} matches none
 * of them, so it is never collected unless named explicitly with
 * {@code -Dtest}. That is deliberate and the class must not be renamed to
 * {@code ...Test} — doing so would make a 20-minute paid run part of every
 * build, with only the two annotations standing between a developer who has a
 * key exported and a surprise bill.
 *
 * <p>Options:
 * <ul>
 *   <li>{@code -Dcvevaluator.harness.sample=15} — first N pairs only, for
 *       shaking out mechanics without a full run
 *   <li>{@code -Dcvevaluator.harness.label=batched-4} — names the run in the
 *       report filename and header, so two configurations can be told apart
 *       afterwards
 *   <li>{@code -Dcvevaluator.harness.delay-ms=6000} — pace the calls, for a key
 *       whose tier cannot sustain a full run back to back
 * </ul>
 *
 * <h2>Quota is the binding constraint</h2>
 *
 * A full run is 60 paid calls, and the plan behind this needs several of them:
 * a baseline, a repeat of the baseline to establish how much the score moves on
 * its own, and one per configuration under test. That is comfortably into the
 * hundreds of calls, and the first full run here died of a 429 seven pairs in.
 * Check the tier the key is on before scheduling a comparison — the harness
 * cannot measure anything the quota will not pay for.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "cvevaluator.harness", matches = "true")
class EvaluationHarness {

    private static final Path FIXTURES = Path.of("fixtures");
    private static final Path REPORTS = Path.of("harness-reports");

    private static final String MODEL = "gemini-2.5-flash";

    /**
     * Consecutive failures before the run gives up on the whole corpus.
     *
     * <p>Five. One or two in a row is a bad response or a blip and the run
     * should carry on; five is the environment being broken - an exhausted
     * quota, a revoked key, no network - and none of those clear up while the
     * loop keeps hammering them.
     */
    private static final int CONSECUTIVE_FAILURE_LIMIT = 5;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SpringAiRetryAutoConfiguration.class,
                    ToolCallingAutoConfiguration.class,
                    ChatObservationAutoConfiguration.class,
                    GoogleGenAiChatAutoConfiguration.class,
                    ChatClientAutoConfiguration.class))
            .withUserConfiguration(EvaluationBeans.class)
            .withPropertyValues(
                    "spring.ai.google.genai.api-key=" + System.getenv("GEMINI_API_KEY"),
                    "spring.ai.google.genai.chat.options.model=" + MODEL);

    @Configuration(proxyBeanMethods = false)
    @Import({AssessmentRubric.class, VerdictCalculator.class, LlmEvaluator.class})
    static class EvaluationBeans {
    }

    @Test
    void scoreTheFixtureCorpus() {
        contextRunner.run(context -> {
            LlmEvaluator evaluator = context.getBean(LlmEvaluator.class);

            List<HarnessManifest.Pair> pairs = selected(HarnessManifest.pairs(FIXTURES));
            HarnessReport report = new HarnessReport(
                    System.getProperty("cvevaluator.harness.label", "single-call"),
                    MODEL,
                    AssessmentRubric.ASSESSMENT_PROMPT_VERSION);

            System.out.printf("Harness: %d pair(s) to evaluate. This will take a while.%n", pairs.size());

            int consecutiveFailures = 0;
            for (int i = 0; i < pairs.size(); i++) {
                HarnessManifest.Pair pair = pairs.get(i);
                System.out.printf("  [%d/%d] %s vs %s%n", i + 1, pairs.size(), pair.cvName(), pair.jobId());

                HarnessReport.PairResult result = evaluate(evaluator, pair);
                report.add(result);

                // Stop rather than grind on. The first full run exhausted the
                // API quota seven pairs in and then spent fifteen minutes
                // collecting fifty-three identical 429s - burning wall time to
                // produce nothing, and making the report look like a grading
                // catastrophe rather than an infrastructure one. Nothing that
                // fails this consistently recovers within the same run.
                consecutiveFailures = result.failed() ? consecutiveFailures + 1 : 0;
                if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
                    System.out.printf("%nABORTED after %d consecutive failures at pair %d of %d.%n"
                                    + "This is the environment, not the grading. Last error:%n  %s%n",
                            consecutiveFailures, i + 1, pairs.size(), result.error());
                    break;
                }

                pauseBetweenCalls();
            }

            System.out.println(report.console());
            System.out.println("Report written to " + report.write(REPORTS).toAbsolutePath());

            // The only assertion. Anything about the scores would make an
            // experiment into a build failure the first time a change traded
            // one metric for another.
            assertThat(report.results()).as("the harness produced no results at all").isNotEmpty();
            assertThat(report.failures())
                    .as("every single pair errored, so this is the harness broken, not the grading")
                    .isNotEqualTo(report.results().size());
        });
    }

    /**
     * One pair, through the same path production uses: extract, redact, evaluate.
     *
     * <p>Errors are captured rather than thrown. One pair that trips a rate limit
     * 40 minutes into a run must not discard the other 59 results.
     */
    private static HarnessReport.PairResult evaluate(LlmEvaluator evaluator, HarnessManifest.Pair pair) {
        try {
            String cvText = cvText(pair.cvFile());
            Job job = job(pair.jobFile());

            EvaluationResult result = evaluator.evaluate(job, cvText);
            List<String> ungrounded = ungroundedQuotes(result, cvText);

            return new HarnessReport.PairResult(
                    pair.cvName(),
                    pair.candidate(),
                    pair.jobId(),
                    pair.expected(),
                    result.verdict(),
                    quotedAssessments(result).size(),
                    quotedAssessments(result).size() - ungrounded.size(),
                    ungrounded,
                    result.tokensIn(),
                    result.tokensOut(),
                    result.latencyMs(),
                    null);

        } catch (RuntimeException | IOException e) {
            String description = describe(e);
            System.out.printf("      failed: %s%n", description);
            return new HarnessReport.PairResult(
                    pair.cvName(), pair.candidate(), pair.jobId(), pair.expected(),
                    null, 0, 0, List.of(), 0, 0, 0L,
                    description);
        }
    }

    /**
     * The whole cause chain, not just the outermost message.
     *
     * <p>Learned the hard way on the first full run: {@code LlmEvaluator} wraps
     * everything the call can throw in one {@code EvaluationParseException} with
     * a fixed message, which is right for a caller that only needs to know the
     * response was unusable. For a diagnostic run it is useless — 53 identical
     * lines saying "could not be read" name neither a rate limit, nor a timeout,
     * nor a schema violation, and those need completely different responses.
     */
    private static String describe(Throwable error) {
        StringBuilder description = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (!description.isEmpty()) {
                description.append(" <- ");
            }
            description.append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(current.getMessage());
            if (current.getCause() == current) {
                break;
            }
        }
        return description.toString();
    }

    /**
     * The groundedness check: is every returned quote actually in the CV?
     *
     * <p>The one metric here that does not depend on the manifest being right.
     * A quote that is not found is a fabrication — the model wrote a sentence
     * and attributed it to a candidate who never said it — and no amount of
     * plausible reasoning around it makes that acceptable.
     *
     * <p>Whitespace is normalised on both sides before comparing, the same
     * tolerance {@code LlmEvaluatorIntegrationTest} uses and the same one the
     * D3 grounding checker will need: PDF extraction breaks a wrapped bullet
     * across lines and a model copying it back joins it with a single space.
     * Requiring a byte-identical match would report fabrication on quotes that
     * are verbatim in every sense a reader cares about.
     */
    private static List<String> ungroundedQuotes(EvaluationResult result, String cvText) {
        String haystack = normalised(cvText);
        List<String> ungrounded = new ArrayList<>();

        for (RequirementAssessment assessment : quotedAssessments(result)) {
            if (!haystack.contains(normalised(assessment.evidenceQuote()))) {
                ungrounded.add(assessment.requirementId() + ": " + assessment.evidenceQuote());
            }
        }
        return ungrounded;
    }

    /**
     * Only MET and PARTIAL can carry a quote — {@code LlmEvaluator.validate}
     * has already rejected the response if a NOT_MET or UNCLEAR did — so those
     * are the assessments there is anything to check.
     */
    private static List<RequirementAssessment> quotedAssessments(EvaluationResult result) {
        return result.assessments().stream()
                .filter(a -> a.status() == RequirementStatus.MET || a.status() == RequirementStatus.PARTIAL)
                .filter(a -> StringUtils.hasText(a.evidenceQuote()))
                .toList();
    }

    /** Redacted, because that is what production passes and what quotes must match. */
    private static String cvText(String relativePath) throws IOException {
        Path file = FIXTURES.resolve(relativePath);
        String extension = StringUtils.getFilenameExtension(file.getFileName().toString());
        String extracted = new CvTextExtractor().extract(file, extension).text();
        return new PiiRedactor().redact(extracted);
    }

    /**
     * The job as the API would have stored it. Version is left at 1 — the
     * harness never re-evaluates, so there is nothing for a version to
     * distinguish.
     */
    private static Job job(String relativePath) throws IOException {
        CreateJobRequest fixture = JsonMapper.builder().build()
                .readValue(FIXTURES.resolve(relativePath).toFile(), CreateJobRequest.class);

        return new Job(fixture.title(), fixture.description(), fixture.seniority(),
                fixture.requirements(), null);
    }

    /**
     * The first N pairs when sampling. First rather than random, so a sampled
     * run compares the same pairs every time — a random sample would make two
     * runs differ for a reason that has nothing to do with the change under
     * test.
     */
    private static List<HarnessManifest.Pair> selected(List<HarnessManifest.Pair> all) {
        String sample = System.getProperty("cvevaluator.harness.sample");
        if (!StringUtils.hasText(sample)) {
            return all;
        }
        int limit = Math.min(Integer.parseInt(sample.trim()), all.size());
        return all.subList(0, limit);
    }

    /**
     * Optional gap between calls, via
     * {@code -Dcvevaluator.harness.delay-ms=6000}.
     *
     * <p>Off by default, because the pacing needed depends entirely on the tier
     * the key is on and guessing wrong just makes every run slower for nothing.
     * A free-tier key with a low requests-per-minute allowance needs this; a
     * paid one does not. Sequential evaluation already spaces calls about
     * twenty seconds apart, so this exists for a per-day or per-minute cap that
     * spacing alone cannot satisfy.
     */
    private static void pauseBetweenCalls() {
        String delay = System.getProperty("cvevaluator.harness.delay-ms");
        if (!StringUtils.hasText(delay)) {
            return;
        }
        try {
            Thread.sleep(Long.parseLong(delay.trim()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while pacing harness calls", e);
        }
    }

    private static String normalised(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
