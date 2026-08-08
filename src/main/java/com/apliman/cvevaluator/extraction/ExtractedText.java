package com.apliman.cvevaluator.extraction;

/**
 * What {@link CvTextExtractor} got out of a file.
 *
 * <p>{@code method} is recorded on the row so that when a candidate says "my CV
 * came out wrong", the first question — which library read it — is already
 * answered without re-running anything.
 *
 * <p>{@code text} may be blank. Deciding that blank means failure is policy and
 * lives in {@link CvIngestionService}, not here.
 */
public record ExtractedText(String text, String method) {
}
