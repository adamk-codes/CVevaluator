package com.apliman.cvevaluator.evaluation.harness;

import com.apliman.cvevaluator.evaluation.RequirementAssessment;
import com.apliman.cvevaluator.evaluation.Verdict;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scores candidate verdict rules against evaluations that already happened.
 *
 * <h2>Why this costs nothing</h2>
 *
 * The verdict is pure Java over the assessments — no model call — so once a
 * harness run has stored its assessments, every alternative rule can be scored
 * against those same real evaluations offline and instantly. One paid run
 * becomes an unlimited free search. That is the whole reason
 * {@code PairResult.assessments} exists.
 *
 * <p>The alternative was a full API run per candidate formula, which is how the
 * shipped thresholds were originally chosen: by reasoning about what they ought
 * to be, with nothing to check them against. That produced a rule under which
 * two of the four verdicts are unreachable, and nothing noticed until 60 pairs
 * were run at once.
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * mvnw test -Dtest=VerdictReplay
 * </pre>
 *
 * No key, no network, no opt-in flag — it only reads {@code harness-reports/}.
 * If there are no reports it skips, so it is safe in a normal build.
 *
 * <h2>What it cannot tell you</h2>
 *
 * The assessments were produced under one model and one prompt version. A rule
 * tuned here is tuned to those statuses. It is still the right way to choose
 * between rules — they are all being scored on identical inputs — but a rule
 * that wins by a single pair is inside the noise and should not be treated as a
 * winner.
 */
class VerdictReplay {

    private static final Path REPORTS = Path.of("harness-reports");

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredReport(Map<String, Object> config, List<StoredPair> pairs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredPair(
            String cv,
            String job,
            Verdict expected,
            Verdict actual,
            List<RequirementAssessment> assessments,
            String error
    ) {
    }

    @Test
    void scoreEveryCandidateRuleAgainstTheStoredRuns() throws IOException {
        List<StoredPair> pairs = scoredPairsFromNewestReport();

        if (pairs.isEmpty()) {
            System.out.println("VerdictReplay: no stored harness report with assessments; skipping. "
                    + "Run EvaluationHarness first.");
            return;
        }

        System.out.printf("%nReplaying %d scored pair(s) against %d candidate rule(s)%n%n",
                pairs.size(), VerdictStrategy.all().size());

        System.out.printf("%-20s %-8s %-9s %-9s %-9s  %s%n",
                "RULE", "EXACT", "ADJACENT", "DISTANT", "BANDS", "DESCRIPTION");
        System.out.println("-".repeat(110));

        for (VerdictStrategy.Named strategy : VerdictStrategy.all()) {
            long exact = 0;
            long adjacent = 0;
            long distant = 0;
            java.util.Set<Verdict> produced = new java.util.LinkedHashSet<>();

            for (StoredPair pair : pairs) {
                Verdict computed = strategy.apply(pair.assessments());
                produced.add(computed);
                int distance = Math.abs(bandOf(pair.expected()) - bandOf(computed));
                if (distance == 0) {
                    exact++;
                } else if (distance == 1) {
                    adjacent++;
                } else {
                    distant++;
                }
            }

            System.out.printf("%-20s %3d/%-4d %-9d %-9d %-9d  %s%n",
                    strategy.name(), exact, pairs.size(), adjacent, distant, produced.size(),
                    strategy.description());
        }

        System.out.println("-".repeat(110));
        printAlwaysNoBaseline(pairs);
        printPerBandBreakdown(pairs);

        assertThat(pairs).as("nothing was replayed").isNotEmpty();
    }

    /**
     * The number every rule has to beat to be worth having.
     *
     * <p>A rule that answers NOT_A_FIT to everything scores this. Reporting it
     * alongside the candidates is what stops a headline agreement figure from
     * looking impressive when most of the corpus is cross-domain pairings that
     * are NOT_A_FIT anyway.
     */
    private static void printAlwaysNoBaseline(List<StoredPair> pairs) {
        long alwaysNo = pairs.stream().filter(p -> p.expected() == Verdict.NOT_A_FIT).count();
        System.out.printf("%nFloor: answering NOT_A_FIT to everything scores %d/%d (%.1f%%). "
                        + "Any rule must beat this to be earning its keep.%n",
                alwaysNo, pairs.size(), alwaysNo * 100.0 / pairs.size());
    }

    /**
     * Per-expected-band accuracy, because the headline hides exactly the failure
     * that prompted this. A rule can score well overall while being incapable of
     * ever producing two of the four verdicts.
     */
    private static void printPerBandBreakdown(List<StoredPair> pairs) {
        System.out.printf("%n%-20s", "RULE");
        for (Verdict expected : Verdict.values()) {
            System.out.printf("%14s", expected.name());
        }
        System.out.println();
        System.out.println("-".repeat(110));

        for (VerdictStrategy.Named strategy : VerdictStrategy.all()) {
            System.out.printf("%-20s", strategy.name());
            for (Verdict expected : Verdict.values()) {
                List<StoredPair> ofBand = pairs.stream()
                        .filter(p -> p.expected() == expected)
                        .toList();
                long right = ofBand.stream()
                        .filter(p -> strategy.apply(p.assessments()) == expected)
                        .count();
                System.out.printf("%14s", ofBand.isEmpty() ? "-" : right + "/" + ofBand.size());
            }
            System.out.println();
        }
        System.out.println();
    }

    /**
     * The newest report that actually carries assessments. Reports written
     * before {@code PairResult.assessments} existed deserialize with a null
     * list and are useless here, so they are skipped rather than crashing the
     * replay.
     */
    private static List<StoredPair> scoredPairsFromNewestReport() throws IOException {
        if (!Files.isDirectory(REPORTS)) {
            return List.of();
        }

        List<Path> newestFirst;
        try (Stream<Path> files = Files.list(REPORTS)) {
            newestFirst = files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(VerdictReplay::lastModified).reversed())
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
                System.out.printf("VerdictReplay: using %s (%s)%n",
                        file.getFileName(), report.config());
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

    private static int bandOf(Verdict verdict) {
        return switch (verdict) {
            case NOT_A_FIT -> 0;
            case WEAK_FIT -> 1;
            case POSSIBLE_FIT -> 2;
            case STRONG_FIT -> 3;
        };
    }
}
