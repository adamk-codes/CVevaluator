package com.apliman.cvevaluator.evaluation;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that every quoted piece of evidence actually appears in the CV.
 *
 * <h2>Why this is the load-bearing check</h2>
 *
 * Everything else the pipeline verifies is structural: the ids match, the
 * statuses are valid enum values, the null-quote rule holds, the reasoning is
 * not blank. All of that can pass while a quote is a sentence the model wrote
 * itself and attributed to the candidate. A wrong verdict is a calibration
 * problem; a fabricated quote presented as the candidate's own words is the
 * central claim of this project failing silently.
 *
 * <p>It is not hypothetical. On the fixture corpus the model produced six
 * quotes on one DOCX that each welded two <em>non-adjacent</em> CV lines
 * together with a newline — one joined an EXPERIENCE bullet to a row of the
 * skills table at the far end of the document. The rubric forbids exactly that
 * ("Never merge two lines into one quote") and nothing caught it, because
 * {@code validate} never sees the CV text.
 *
 * <h2>Whitespace is normalised; nothing else is</h2>
 *
 * PDF and DOCX extraction breaks a wrapped bullet across lines, and a model
 * copying it back joins it with a single space. Requiring a byte-identical
 * match would report fabrication on quotes that are verbatim in every sense a
 * reader cares about. So both sides are collapsed to single spaces before
 * comparing.
 *
 * <p>Nothing beyond that. No case folding, no punctuation stripping, no fuzzy
 * or near-match scoring. Each of those would let a real alteration through, and
 * "close enough to what the candidate wrote" is not a claim this project makes.
 * The merged-lines case above is caught precisely because the two fragments are
 * not adjacent in the source — a fuzzy matcher tuned to forgive it would also
 * forgive a rewritten one.
 */
@Component
public class GroundingChecker {

    /**
     * One quote that could not be found in the CV.
     *
     * @param quote kept whole rather than truncated — the failure message is
     *              read by whoever has to decide whether the model rewrote a
     *              line or invented one, and that is not decidable from a
     *              prefix
     */
    public record Ungrounded(String requirementId, String quote) {
    }

    /**
     * Every quote in {@code assessments} that is not present in {@code cvText}.
     *
     * <p>Returns findings rather than throwing, so the policy — reject the
     * evaluation, or store it flagged — lives with the caller and this class
     * stays a pure function that is trivial to test.
     *
     * @param cvText the same text the model was given. Passing anything else,
     *               in particular the unredacted original when the model saw the
     *               redacted copy, turns every redacted span into a false
     *               fabrication report.
     */
    public List<Ungrounded> check(List<RequirementAssessment> assessments, String cvText) {
        if (assessments == null || assessments.isEmpty()) {
            return List.of();
        }

        String haystack = normalise(cvText);
        List<Ungrounded> ungrounded = new ArrayList<>();

        for (RequirementAssessment assessment : assessments) {
            String quote = assessment.evidenceQuote();
            if (!StringUtils.hasText(quote)) {
                // No quote to check. Whether one was *required* is the
                // null-quote rule's business, not this class's.
                continue;
            }
            if (!haystack.contains(normalise(quote))) {
                ungrounded.add(new Ungrounded(assessment.requirementId(), quote));
            }
        }
        return List.copyOf(ungrounded);
    }

    private static String normalise(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }
}
