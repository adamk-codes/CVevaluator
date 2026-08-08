package com.apliman.cvevaluator.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Storage configuration, bound from {@code cvevaluator.storage.*}.
 */
@ConfigurationProperties("cvevaluator.storage")
public record StorageProperties(Path location, Set<String> allowedExtensions) {

    private static final Path DEFAULT_LOCATION = Path.of("cv-uploads");

    /** PDFBox reads pdf; POI XWPF reads docx. Nothing else is extractable yet. */
    private static final Set<String> DEFAULT_EXTENSIONS = Set.of("pdf", "docx");

    public StorageProperties {
        if (location == null) {
            location = DEFAULT_LOCATION;
        }
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            allowedExtensions = DEFAULT_EXTENSIONS;
        } else {
            // Both sides of the allowlist comparison have to be folded the same
            // way. Without this, allowed-extensions=PDF in a properties file
            // silently rejects every upload, because the incoming extension is
            // lower-cased and the allowlist entry is not.
            allowedExtensions = allowedExtensions.stream()
                    .map(extension -> extension.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
