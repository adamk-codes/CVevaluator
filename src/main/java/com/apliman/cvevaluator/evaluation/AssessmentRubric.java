package com.apliman.cvevaluator.evaluation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the rubric text and pins the version it was loaded at.
 *
 * <p>The rubric lives in {@code prompts/assessment-rubric.md} rather than in a
 * Java text block because it is edited far more often than this class is, and a
 * prompt buried in a string constant is a prompt nobody reviews. It is read once
 * at startup, not per call.
 */
@Component
public class AssessmentRubric {

    /**
     * <strong>Bump this on every edit to {@code assessment-rubric.md}.</strong>
     * Every stored evaluation records the version that produced it, so two
     * evaluations are only comparable when this string matches. Editing the
     * rubric without bumping it is the one change that silently rewrites the
     * meaning of history: old rows keep claiming they were judged by rules that
     * no longer exist, and nothing in the data reveals it.
     *
     * <p>A hand-maintained constant rather than a file hash on purpose. A hash
     * would move on a typo fix, which is exactly the change that should
     * <em>not</em> invalidate a comparison, and it is not something you can read
     * out loud in a demo.
     *
     * <h2>History</h2>
     * <ul>
     *   <li>{@code v1} — first version. Grounding rules, four-value status,
     *       null-quote rule, 0/3/5 anchors on both dimensions.
     * </ul>
     */
    public static final String ASSESSMENT_PROMPT_VERSION = "v1";

    private final String text;

    public AssessmentRubric(@Value("classpath:prompts/assessment-rubric.md") Resource rubric) {
        try {
            this.text = rubric.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Fail the context rather than start with an empty rubric. An
            // evaluator running on a blank system prompt still returns
            // well-formed JSON, so this failure would otherwise show up as
            // quietly worse grades rather than as an error.
            throw new UncheckedIOException("Could not read the assessment rubric from the classpath", e);
        }
    }

    /** The system message, verbatim. */
    public String text() {
        return text;
    }
}
