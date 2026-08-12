package com.apliman.cvevaluator.evaluation.harness;

import com.apliman.cvevaluator.evaluation.RequirementAssessment;
import com.apliman.cvevaluator.evaluation.Verdict;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asks a model for the verdict, given assessments that already exist.
 *
 * <h2>The case for trying this</h2>
 *
 * Every arithmetic rule in {@link VerdictStrategy} tops out at 3/7 on
 * {@code POSSIBLE_FIT}, and they all top out at the <em>same</em> 3/7. That is
 * not a tuning failure, it is the ceiling of counting requirements: a formula
 * cannot know that missing "5+ years of Java" matters more than missing
 * "Kubernetes", because to a ratio both are one must-have. A reader of the
 * reasoning can. Borderline is exactly where that distinction decides the band,
 * which is why every rule fails there and nowhere else.
 *
 * <h2>What it is given, and what it is not</h2>
 *
 * Only the assessments — requirement text, kind, status, reasoning, quote. Not
 * the CV. That is deliberate on two counts: the evidence has already been
 * validated and grounded, so the judge cannot invent support that the pipeline
 * would have rejected; and it keeps the input small enough that a stronger, more
 * expensive model is affordable.
 *
 * <p>Dimension scores are withheld too, matching the existing rule that they
 * never feed the verdict — a well-written CV must not outrank one that meets
 * the requirements.
 *
 * <h2>What adopting this would cost</h2>
 *
 * Reproducibility. The arithmetic gives the same verdict for the same
 * assessments forever; a model does not, even at temperature 0. It is a weaker
 * concession than letting the model pick the verdict during the original call —
 * the assessments are fixed and quoted before the judge sees them, so the
 * evidence chain survives — but it is a real one, and it is the thing to weigh
 * against whatever accuracy this buys.
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * mvnw test -Dtest=AiVerdictJudge -Dcvevaluator.harness=true
 *           [-Dcvevaluator.judge.model=claude-sonnet-5]
 *           [-Dcvevaluator.harness.sample=20]
 * </pre>
 */
@EnabledIfSystemProperty(named = "cvevaluator.harness", matches = "true")
class AiVerdictJudge {

    private static final Path REPORTS = Path.of("harness-reports");

    /**
     * Haiku by default — the same model that produced the assessments, so a
     * difference in the table is a difference in the <em>method</em> rather
     * than in model strength. Override to compare a stronger judge.
     */
    private static final String DEFAULT_JUDGE_MODEL = "claude-haiku-4-5-20251001";

    private static final String SYSTEM_PROMPT = """
            # Role

            You assign the final hiring band for one candidate against one job. \
            Another system has already read the CV and assessed every requirement \
            individually, quoting the CV for each one. You are not re-reading the CV \
            and you cannot see it. You are deciding what those findings add up to.

            # Task

            Read the per-requirement findings, then choose exactly one band.

            # The bands

            - STRONG_FIT: Meets the required list with evidence. Would shortlist.
            - POSSIBLE_FIT: Real overlap, but a named gap in a required area. A human \
            would argue about this one.
            - WEAK_FIT: Same broad domain, clearly under-qualified, or transferable \
            experience only.
            - NOT_A_FIT: A different profession. No reasonable reading shortlists this.

            # Constraints

            - Weigh which requirement was missed, not how many. A missing core \
            requirement outweighs several missing peripheral ones.
            - MUST_HAVE requirements decide the band. NICE_TO_HAVE requirements \
            separate candidates who already clear the same bar.
            - NOT_A_FIT means wrong profession, not "missing something". A candidate \
            in the right field who is under-qualified is WEAK_FIT.
            - UNCLEAR means the CV was silent, not that the candidate lacks the skill. \
            Treat it as unproven rather than as disproven.
            - Judge only from the findings given. Do not assume anything the \
            assessments do not state.
            - Write the reasoning before the verdict, and make it specific enough to \
            argue with: name the requirement that decided it.
            """;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredReport(Map<String, Object> config, List<StoredPair> pairs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredPair(
            String cv, String job, Verdict expected, Verdict actual,
            List<RequirementAssessment> assessments, String error) {
    }

    private final String judgeModel = System.getProperty("cvevaluator.judge.model", DEFAULT_JUDGE_MODEL);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SpringAiRetryAutoConfiguration.class,
                    ToolCallingAutoConfiguration.class,
                    AnthropicChatAutoConfiguration.class,
                    ChatClientAutoConfiguration.class))
            .withPropertyValues(
                    "spring.ai.anthropic.api-key=" + System.getenv("ANTHROPIC_API_KEY"),
                    "spring.ai.anthropic.chat.options.model=" + judgeModel,
                    "spring.ai.model.chat=anthropic");

    @Test
    void judgeTheStoredAssessments() throws IOException {
        if (!StringUtils.hasText(System.getenv("ANTHROPIC_API_KEY"))) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is not set.");
        }

        List<StoredPair> pairs = sampled(scoredPairsFromNewestReport());
        assertThat(pairs).as("no stored report with assessments; run EvaluationHarness first").isNotEmpty();

        contextRunner.run(context -> {
            ChatClient.Builder builder = context.getBean(ChatClient.Builder.class)
                    .defaultSystem(SYSTEM_PROMPT)
                    // Same brace problem as LlmEvaluator: the digest and the
                    // generated schema both contain JSON.
                    .defaultTemplateRenderer(new NoOpTemplateRenderer());

            // The Claude 5 family rejects temperature outright - every request
            // comes back 400 "temperature is deprecated for this model" before
            // a single token is processed. Sending it anyway would make a
            // stronger judge untestable for a reason that has nothing to do
            // with judging.
            if (supportsTemperature(judgeModel)) {
                builder = builder.defaultOptions(ChatOptions.builder().temperature(0.0));
            } else {
                System.out.printf("Note: %s does not accept temperature; omitting it.%n", judgeModel);
            }

            ChatClient chatClient = builder.build();

            System.out.printf("%nJudging %d pair(s) with %s%n%n", pairs.size(), judgeModel);

            Map<String, Verdict> judged = new LinkedHashMap<>();
            int exact = 0;
            int adjacent = 0;
            int distant = 0;
            int failed = 0;

            for (StoredPair pair : pairs) {
                try {
                    JudgedVerdict result = chatClient.prompt()
                            .user(digest(pair))
                            .call()
                            .entity(JudgedVerdict.class);

                    judged.put(key(pair), result.verdict());
                    int distance = Math.abs(bandOf(pair.expected()) - bandOf(result.verdict()));
                    if (distance == 0) {
                        exact++;
                    } else if (distance == 1) {
                        adjacent++;
                    } else {
                        distant++;
                    }

                    System.out.printf("  %-28s %-24s expected %-13s judged %-13s %s%n",
                            pair.cv(), pair.job(), pair.expected(), result.verdict(),
                            distance == 0 ? "" : "<-- " + trim(result.reasoning()));

                } catch (RuntimeException e) {
                    failed++;
                    System.out.printf("  %-28s %-24s FAILED: %s%n", pair.cv(), pair.job(), e.getMessage());
                }
            }

            report(pairs, judged, exact, adjacent, distant, failed);
        });
    }

    /**
     * The findings, as compact text. Grouped must-haves first because they
     * decide the band and the ordering says so without needing a sentence to.
     */
    private static String digest(StoredPair pair) {
        StringBuilder digest = new StringBuilder(1024);
        digest.append("# Findings for one candidate against job: ").append(pair.job()).append("\n\n");

        for (RequirementAssessment assessment : pair.assessments().stream()
                .sorted(Comparator.comparing(a -> a.requirementKind().name()))
                .toList()) {
            digest.append("- [").append(assessment.requirementKind()).append("] ")
                    .append(assessment.requirementId()).append(": ")
                    .append(assessment.requirementText()).append('\n')
                    .append("  status: ").append(assessment.status()).append('\n')
                    .append("  finding: ").append(assessment.reasoning()).append('\n');
            if (StringUtils.hasText(assessment.evidenceQuote())) {
                digest.append("  quoted from CV: \"")
                        .append(assessment.evidenceQuote().replaceAll("\\s+", " "))
                        .append("\"\n");
            }
            digest.append('\n');
        }
        return digest.toString();
    }

    private void report(List<StoredPair> pairs, Map<String, Verdict> judged,
                        int exact, int adjacent, int distant, int failed) {
        int scored = exact + adjacent + distant;

        System.out.println("\n" + "-".repeat(96));
        System.out.printf("AI JUDGE (%s)%n", judgeModel);
        System.out.printf("Exact agreement    %d / %d  (%.1f%%)%n",
                exact, scored, scored == 0 ? 0.0 : exact * 100.0 / scored);
        System.out.printf("Adjacent misses    %d%n", adjacent);
        System.out.printf("Distant misses     %d%n", distant);
        System.out.printf("Failures           %d%n", failed);

        System.out.printf("%n%-22s", "PER BAND");
        for (Verdict band : Verdict.values()) {
            System.out.printf("%14s", band.name());
        }
        System.out.println();

        System.out.printf("%-22s", "ai-judge");
        for (Verdict band : Verdict.values()) {
            List<StoredPair> ofBand = pairs.stream().filter(p -> p.expected() == band).toList();
            long right = ofBand.stream().filter(p -> judged.get(key(p)) == band).count();
            System.out.printf("%14s", ofBand.isEmpty() ? "-" : right + "/" + ofBand.size());
        }
        System.out.println();

        // The rules from the free replay, on exactly the pairs the judge saw, so
        // the two are comparable rather than merely adjacent in the transcript.
        for (VerdictStrategy.Named strategy : VerdictStrategy.all()) {
            System.out.printf("%-22s", strategy.name());
            for (Verdict band : Verdict.values()) {
                List<StoredPair> ofBand = pairs.stream().filter(p -> p.expected() == band).toList();
                long right = ofBand.stream()
                        .filter(p -> strategy.apply(p.assessments()) == band)
                        .count();
                System.out.printf("%14s", ofBand.isEmpty() ? "-" : right + "/" + ofBand.size());
            }
            System.out.println();
        }
        System.out.println("-".repeat(96));
    }

    private static List<StoredPair> sampled(List<StoredPair> all) {
        String sample = System.getProperty("cvevaluator.harness.sample");
        if (!StringUtils.hasText(sample)) {
            return all;
        }
        return all.subList(0, Math.min(Integer.parseInt(sample.trim()), all.size()));
    }

    private static List<StoredPair> scoredPairsFromNewestReport() throws IOException {
        if (!Files.isDirectory(REPORTS)) {
            return List.of();
        }
        List<Path> newestFirst;
        try (Stream<Path> files = Files.list(REPORTS)) {
            newestFirst = files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(AiVerdictJudge::lastModified).reversed())
                    .toList();
        }

        JsonMapper mapper = JsonMapper.builder().build();
        for (Path file : newestFirst) {
            StoredReport report = mapper.readValue(file.toFile(), StoredReport.class);
            List<StoredPair> usable = report.pairs().stream()
                    .filter(pair -> !StringUtils.hasText(pair.error()))
                    .filter(pair -> pair.assessments() != null && !pair.assessments().isEmpty())
                    .toList();
            if (!usable.isEmpty()) {
                System.out.printf("AiVerdictJudge: using %s%n", file.getFileName());
                return usable;
            }
        }
        return List.of();
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String key(StoredPair pair) {
        return pair.cv() + "|" + pair.job();
    }

    private static String trim(String reasoning) {
        String single = reasoning == null ? "" : reasoning.replaceAll("\\s+", " ");
        return single.length() <= 70 ? single : single.substring(0, 69) + "…";
    }

    /**
     * Whether a model accepts a {@code temperature}.
     *
     * <p>Matched on the {@code -5} suffix of the Claude 5 family
     * ({@code claude-sonnet-5}, {@code claude-opus-5}, {@code claude-fable-5}),
     * which reject it. A name check is admittedly brittle, but the alternatives
     * are worse: there is no capability flag to query, and discovering it by
     * catching the 400 would mean a failed call per pair.
     *
     * <p>Only the judge needs this. {@code LlmEvaluator} runs on Gemini, which
     * still takes a temperature, and setting 0 there is what makes an
     * evaluation reproducible.
     */
    private static boolean supportsTemperature(String model) {
        return !model.matches(".*-5(-\\d+)?$");
    }

    private static int bandOf(Verdict verdict) {
        return switch (verdict) {
            case NOT_A_FIT -> 0;
            case WEAK_FIT -> 1;
            case POSSIBLE_FIT -> 2;
            case STRONG_FIT -> 3;
        };
    }
}
