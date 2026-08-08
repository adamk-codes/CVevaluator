package com.apliman.cvevaluator.extraction;

/**
 * A file that could not be read at all — corrupt, encrypted, or not the format
 * its extension claims.
 *
 * <p>Every message is authored in {@link CvTextExtractor} and is written to be
 * shown to the candidate: it names no path, no library and no internal state.
 * {@link CvIngestionService} relies on that and copies the message straight into
 * {@code Application.failureReason}. If you add a throw site, keep that
 * property — anything else must be logged instead.
 */
public class TextExtractionException extends RuntimeException {

    public TextExtractionException(String message) {
        super(message);
    }

    public TextExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
