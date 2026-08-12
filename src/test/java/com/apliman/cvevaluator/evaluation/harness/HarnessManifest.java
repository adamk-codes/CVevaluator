package com.apliman.cvevaluator.evaluation.harness;

import com.apliman.cvevaluator.evaluation.Verdict;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code fixtures/manifest.json}, read as the answer key.
 *
 * <h2>What this file is, and what it is not</h2>
 *
 * The labels are the fixture author's judgements, written by reading each CV
 * against each job <em>before</em> any model existed to disagree with them.
 * That independence is what makes them usable as a key at all — a key derived
 * from model output would only ever confirm what the model already does.
 *
 * <p>They are still judgements, not ground truth. When the harness reports a
 * disagreement the right first move is to re-read the CV, because sometimes the
 * label is the thing that is wrong. The harness finds disagreements; it does not
 * settle them.
 */
public final class HarnessManifest {

    /**
     * One CV against one job, with the label the author gave that pairing.
     *
     * @param cvFile   relative to {@code fixtures/}, e.g. {@code cvs/cv-01-....pdf}
     * @param jobFile  relative to {@code fixtures/}
     * @param expected the manifest label, already mapped onto {@link Verdict}
     */
    public record Pair(
            String cvFile,
            String candidate,
            String jobId,
            String jobFile,
            Verdict expected
    ) {

        /** Just the filename, for report rows that do not need the directory. */
        public String cvName() {
            return Path.of(cvFile).getFileName().toString();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Root(List<JobEntry> jobs, List<CvEntry> cvs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobEntry(String id, String file) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CvEntry(String file, String candidate, Map<String, String> against) {
    }

    private HarnessManifest() {
    }

    /**
     * Every labelled pairing in the corpus: 20 CVs against 3 jobs, in CV order
     * then job order, so two runs enumerate them identically.
     *
     * <p>Order matters more than it looks. A sampled run takes the first N of
     * this list, and a sample that is not stable across runs would compare
     * different pairs each time.
     */
    public static List<Pair> pairs(Path fixturesRoot) throws IOException {
        Root root = JsonMapper.builder().build()
                .readValue(fixturesRoot.resolve("manifest.json").toFile(), Root.class);

        Map<String, String> jobFilesById = new java.util.LinkedHashMap<>();
        for (JobEntry job : root.jobs()) {
            jobFilesById.put(job.id(), job.file());
        }

        List<Pair> pairs = new ArrayList<>();
        for (CvEntry cv : root.cvs()) {
            // Iterating the job list rather than the CV's own `against` map, so
            // every CV is paired against every job in the same order even if a
            // future manifest lists them inconsistently.
            for (JobEntry job : root.jobs()) {
                String label = cv.against().get(job.id());
                if (label == null) {
                    throw new IllegalStateException(
                            "manifest.json: " + cv.file() + " has no label against " + job.id());
                }
                pairs.add(new Pair(cv.file(), cv.candidate(), job.id(),
                        jobFilesById.get(job.id()), toVerdict(label)));
            }
        }
        return List.copyOf(pairs);
    }

    /**
     * The manifest's band names onto {@link Verdict}.
     *
     * <p>A 1:1 mapping, but the two vocabularies were written independently and
     * only two of the four names actually coincide. Doing this by
     * {@code Verdict.valueOf} would silently work for {@code NOT_A_FIT} and
     * throw for the rest, so it is spelled out.
     */
    private static Verdict toVerdict(String manifestLabel) {
        return switch (manifestLabel) {
            case "STRONG" -> Verdict.STRONG_FIT;
            case "BORDERLINE" -> Verdict.POSSIBLE_FIT;
            case "WEAK" -> Verdict.WEAK_FIT;
            case "NOT_A_FIT" -> Verdict.NOT_A_FIT;
            default -> throw new IllegalStateException(
                    "manifest.json uses an unknown band: " + manifestLabel);
        };
    }
}
