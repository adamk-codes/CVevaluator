package com.apliman.cvevaluator.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Extraction configuration, bound from {@code cvevaluator.extraction.*}.
 */
@ConfigurationProperties("cvevaluator.extraction")
public record ExtractionProperties(Integer minimumTextLength) {

    /**
     * Roughly one line of prose. Chosen to sit far below any real CV — the
     * shortest fixture in the corpus is about 900 characters — and far above the
     * handful of stray characters a scanned PDF produces.
     */
    private static final int DEFAULT_MINIMUM_TEXT_LENGTH = 100;

    public ExtractionProperties {
        // A configured 0 is honoured: it means "accept anything non-blank", which
        // is a reasonable thing to want while debugging a specific file.
        if (minimumTextLength == null || minimumTextLength < 0) {
            minimumTextLength = DEFAULT_MINIMUM_TEXT_LENGTH;
        }
    }
}
