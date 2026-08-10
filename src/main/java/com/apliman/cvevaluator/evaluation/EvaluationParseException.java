package com.apliman.cvevaluator.evaluation;

/**
 * The model returned something that is not a usable evaluation.
 *
 * <p>Covers both "the JSON did not parse" and "it parsed but broke the
 * contract" — a skipped requirement id, an invented one, a score of 7, a quote
 * attached to an {@code UNCLEAR}. One type for both because the caller's
 * response is the same in either case: this evaluation does not exist, mark the
 * row FAILED, keep the reason.
 *
 * <p>A distinct type rather than {@code IllegalStateException} on purpose. The
 * re-evaluation path catches <em>this</em> specifically, so an unrelated bug in
 * the same try block surfaces as a bug instead of being recorded as "the model
 * misbehaved". That distinction is the difference between a demo where a broken
 * row is explainable and one where it is not.
 *
 * <p>Messages are written to be readable on a status endpoint: they say what
 * was wrong with the response, and never contain CV text.
 */
public class EvaluationParseException extends RuntimeException {

    public EvaluationParseException(String message) {
        super(message);
    }

    public EvaluationParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
