package com.apliman.cvevaluator.evaluation.harness;

import com.apliman.cvevaluator.evaluation.RequirementAssessment;
import com.apliman.cvevaluator.evaluation.Verdict;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects per-pair results and renders them as a console table and a JSON file.
 *
 * <h2>Reading the numbers</h2>
 *
 * <ul>
 *   <li><strong>Exact agreement</strong> — the headline. How many of the pairs
 *       came out with the verdict the manifest expects.
 *   <li><strong>Adjacent vs distant</strong> — a miss by one band
 *       ({@code STRONG_FIT} where {@code POSSIBLE_FIT} was expected) and a miss
 *       by three are the same single point off the headline and are not the same
 *       failure. A grader that is mostly adjacent is miscalibrated; one that is
 *       distant is not reading the CV.
 *   <li><strong>Groundedness</strong> — of every quote returned, how many are
 *       actually present in the CV text. This needs no labels, so unlike
 *       everything else here it cannot be wrong because the manifest is wrong.
 *       It is the number that most directly supports the project's claim, and
 *       anything below 100% is a fabrication worth reading by hand.
 * </ul>
 */
public final class HarnessReport {

    /**
     * One evaluated pair.
     *
     * <p>{@code assessments} is the expensive part of the payload and the reason
     * it is here: the verdict is computed from it by pure Java, so a stored copy
     * lets any number of alternative verdict rules be replayed against real
     * evaluations at zero API cost. Without it, every candidate scoring formula
     * needs its own full run. See {@code VerdictReplay}.
     *
     * @param error non-null when the evaluation threw; the pair then counts as
     *              a failure rather than a disagreement, because no verdict was
     *              produced to disagree with
     */
    public record PairResult(
            String cv,
            String candidate,
            String job,
            Verdict expected,
            Verdict actual,
            List<RequirementAssessment> assessments,
            int quotesChecked,
            int quotesGrounded,
            List<String> ungroundedQuotes,
            int tokensIn,
            int tokensOut,
            long latencyMs,
            String error
    ) {

        public boolean failed() {
            return error != null;
        }

        public boolean matched() {
            return !failed() && expected == actual;
        }

        /**
         * Bands apart, on the ordering NOT_A_FIT &lt; WEAK &lt; POSSIBLE &lt;
         * STRONG. Relies on {@link Verdict}'s declaration order being worst-last;
         * {@link #bandOf} does the translation rather than using the ordinal
         * directly, so reordering the enum cannot silently invert this.
         */
        public int distance() {
            return failed() ? -1 : Math.abs(bandOf(expected) - bandOf(actual));
        }
    }

    private final String label;
    private final String model;
    private final String promptVersion;
    private final List<PairResult> results = new ArrayList<>();

    public HarnessReport(String label, String model, String promptVersion) {
        this.label = label;
        this.model = model;
        this.promptVersion = promptVersion;
    }

    public void add(PairResult result) {
        results.add(result);
    }

    public List<PairResult> results() {
        return List.copyOf(results);
    }

    public long matched() {
        return results.stream().filter(PairResult::matched).count();
    }

    public long failures() {
        return results.stream().filter(PairResult::failed).count();
    }

    private static int bandOf(Verdict verdict) {
        return switch (verdict) {
            case NOT_A_FIT -> 0;
            case WEAK_FIT -> 1;
            case POSSIBLE_FIT -> 2;
            case STRONG_FIT -> 3;
        };
    }

    /**
     * Writes the JSON report and returns where it landed.
     *
     * <p>Under {@code harness-reports/} at the project root rather than
     * {@code target/}, so a baseline run survives {@code mvn clean}. Comparing a
     * later run against a baseline that a build wiped is the whole point of
     * keeping it, and the directory is gitignored so reports are never committed
     * by accident.
     */
    public Path write(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("eval-report-" + label + "-" + Instant.now().toEpochMilli() + ".json");

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("config", Map.of(
                "label", label,
                "model", model,
                "promptVersion", promptVersion,
                "runAt", Instant.now().toString(),
                "pairs", results.size()));
        document.put("summary", summary());
        document.put("confusion", confusion());
        document.put("pairs", results);

        Files.writeString(file,
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(document),
                StandardCharsets.UTF_8);
        return file;
    }

    private Map<String, Object> summary() {
        long scored = results.stream().filter(r -> !r.failed()).count();
        long adjacent = results.stream().filter(r -> r.distance() == 1).count();
        long distant = results.stream().filter(r -> r.distance() >= 2).count();
        int quotesChecked = results.stream().mapToInt(PairResult::quotesChecked).sum();
        int quotesGrounded = results.stream().mapToInt(PairResult::quotesGrounded).sum();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("pairs", results.size());
        summary.put("scored", scored);
        summary.put("failures", failures());
        summary.put("exactAgreement", matched());
        summary.put("agreementRate", rate(matched(), scored));
        summary.put("adjacentMisses", adjacent);
        summary.put("distantMisses", distant);
        summary.put("quotesChecked", quotesChecked);
        summary.put("quotesGrounded", quotesGrounded);
        summary.put("groundednessRate", rate(quotesGrounded, quotesChecked));
        summary.put("totalTokensIn", results.stream().mapToLong(PairResult::tokensIn).sum());
        summary.put("totalTokensOut", results.stream().mapToLong(PairResult::tokensOut).sum());
        summary.put("totalLatencyMs", results.stream().mapToLong(PairResult::latencyMs).sum());
        return summary;
    }

    /** Expected verdict to actual verdict counts, so the shape of the misses is visible. */
    private Map<String, Map<String, Long>> confusion() {
        Map<String, Map<String, Long>> matrix = new LinkedHashMap<>();
        for (Verdict expected : Verdict.values()) {
            Map<String, Long> row = new LinkedHashMap<>();
            for (Verdict actual : Verdict.values()) {
                row.put(actual.name(), results.stream()
                        .filter(r -> !r.failed() && r.expected() == expected && r.actual() == actual)
                        .count());
            }
            matrix.put(expected.name(), row);
        }
        return matrix;
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : Math.round(numerator * 10_000.0 / denominator) / 10_000.0;
    }

    /** The human-readable version, printed at the end of a run. */
    public String console() {
        StringBuilder out = new StringBuilder(2048);
        long scored = results.stream().filter(r -> !r.failed()).count();

        out.append("\n").append("=".repeat(78)).append('\n');
        out.append("EVALUATION HARNESS — ").append(label).append('\n');
        out.append("model=").append(model).append("  promptVersion=").append(promptVersion).append('\n');
        out.append("=".repeat(78)).append("\n\n");

        out.append(String.format("%-28s %-22s %-18s %s%n", "CV", "JOB", "EXPECTED", "ACTUAL"));
        out.append("-".repeat(78)).append('\n');
        for (PairResult r : results) {
            String actual = r.failed() ? "ERROR" : r.actual().name();
            String mark = r.failed() ? "!" : r.matched() ? " " : (r.distance() == 1 ? "~" : "X");
            out.append(String.format("%s %-27s %-22s %-18s %s%n",
                    mark, truncate(r.cv(), 27), truncate(r.job(), 22), r.expected().name(), actual));
        }

        out.append("\n").append("-".repeat(78)).append('\n');
        out.append(String.format("Exact agreement    %d / %d  (%.1f%%)%n",
                matched(), scored, scored == 0 ? 0.0 : matched() * 100.0 / scored));
        out.append(String.format("Adjacent misses    %d   (one band off)%n",
                results.stream().filter(r -> r.distance() == 1).count()));
        out.append(String.format("Distant misses     %d   (two or more bands off)%n",
                results.stream().filter(r -> r.distance() >= 2).count()));
        out.append(String.format("Failures           %d%n", failures()));

        int checked = results.stream().mapToInt(PairResult::quotesChecked).sum();
        int grounded = results.stream().mapToInt(PairResult::quotesGrounded).sum();
        out.append(String.format("Quote groundedness %d / %d  (%.2f%%)%s%n",
                grounded, checked, checked == 0 ? 0.0 : grounded * 100.0 / checked,
                grounded == checked ? "" : "   <-- FABRICATED QUOTES, read these by hand"));

        out.append(String.format("Tokens             %,d in / %,d out%n",
                results.stream().mapToLong(PairResult::tokensIn).sum(),
                results.stream().mapToLong(PairResult::tokensOut).sum()));
        out.append(String.format("Model time         %,d ms total%n",
                results.stream().mapToLong(PairResult::latencyMs).sum()));

        List<PairResult> ungrounded = results.stream()
                .filter(r -> !r.ungroundedQuotes().isEmpty())
                .toList();
        if (!ungrounded.isEmpty()) {
            out.append("\nUngrounded quotes:\n");
            for (PairResult r : ungrounded) {
                for (String quote : r.ungroundedQuotes()) {
                    // Whitespace collapsed for display only. A quote that merges
                    // two CV lines contains a newline, which is exactly the
                    // interesting case and exactly the one that would otherwise
                    // wrap across console rows and hide the join. The stored
                    // JSON keeps the original.
                    out.append("  ").append(r.cv()).append(" / ").append(r.job())
                            .append(": ").append(truncate(quote.replaceAll("\\s+", " "), 96)).append('\n');
                }
            }
        }

        out.append("=".repeat(78)).append('\n');
        return out.toString();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
