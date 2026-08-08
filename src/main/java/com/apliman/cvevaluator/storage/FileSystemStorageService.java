package com.apliman.cvevaluator.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FileSystemStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(FileSystemStorageService.class);

    private final Path root;
    private final Set<String> allowedExtensions;
    private final String allowedExtensionsForMessage;

    public FileSystemStorageService(StorageProperties properties) {
        // Absolute and normalized once, here. resolveWithinRoot compares candidate
        // paths against this field, and that comparison is meaningless if the root
        // is relative or still contains "." / ".." segments.
        this.root = properties.location().toAbsolutePath().normalize();
        this.allowedExtensions = properties.allowedExtensions();
        this.allowedExtensionsForMessage = allowedExtensions.stream()
                .sorted()
                .collect(Collectors.joining(", "));
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            // Fail at startup rather than on the first upload.
            throw new StorageException("Could not create storage root " + root, e);
        }
        log.info("CV storage root: {} (allowed extensions: {})", root, allowedExtensionsForMessage);
    }

    @Override
    public StoredFile store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidUploadException("Uploaded file is empty");
        }

        String originalFilename = cleanBasename(file.getOriginalFilename());
        String extension = allowedExtensionOf(originalFilename);

        // The client never names the file on disk. A hostile originalFilename cannot
        // steer the write, and two candidates uploading "cv.pdf" never collide.
        String storageKey = UUID.randomUUID() + "." + extension;
        Path target = resolveWithinRoot(storageKey);

        try (InputStream in = file.getInputStream()) {
            // Deliberately no REPLACE_EXISTING. A UUIDv4 collision means something
            // is badly wrong, and overwriting another candidate's CV is worse than
            // failing loudly.
            Files.copy(in, target);
        } catch (IOException e) {
            throw new StorageException("Could not store upload as " + target, e);
        }

        return new StoredFile(storageKey, originalFilename, extension, file.getSize());
    }

    @Override
    public Path load(String storageKey) {
        Path file = resolveWithinRoot(storageKey);
        if (!Files.isReadable(file)) {
            throw new StorageException("Stored file is missing or unreadable: " + file);
        }
        return file;
    }

    /**
     * Proves that a path derived from an untrusted string is inside {@link #root},
     * and returns it.
     *
     * <p>Three pieces, each load-bearing:
     * <ul>
     *   <li>{@code resolve} — an <em>absolute</em> key resolves to itself rather
     *       than being appended to the root, so {@code /etc/passwd} falls out of
     *       the root and is caught by the same check. No separate branch needed.
     *   <li>{@code normalize} — collapses {@code ..} so the comparison sees the
     *       real target. Without it {@code ../../etc/passwd} passes any prefix
     *       check at all.
     *   <li>{@code Path.startsWith} — compares <em>name elements</em>, not
     *       characters. This is the part people get wrong:
     *       {@code "/data/uploads-evil/x.pdf".startsWith("/data/uploads")} is
     *       {@code true} as a String even though the path is outside the root.
     *       {@code Path.startsWith} returns false, correctly, because
     *       {@code uploads-evil} is not the element {@code uploads}.
     * </ul>
     *
     * <p>The rejection message is generic on purpose — the handler returns it to
     * the client and the absolute root is not the client's business. Details go
     * to the log.
     */
    private Path resolveWithinRoot(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            throw new InvalidUploadException("Invalid file name");
        }

        Path candidate;
        try {
            candidate = root.resolve(storageKey).normalize();
        } catch (InvalidPathException e) {
            // Platform-illegal characters (NUL, ":" on Windows, ...). Converted
            // here because InvalidPathException extends IllegalArgumentException,
            // which GlobalExceptionHandler answers by echoing the raw message.
            log.warn("Rejected unparseable storage key: {}", storageKey);
            throw new InvalidUploadException("Invalid file name");
        }

        if (!candidate.startsWith(root)) {
            log.warn("Rejected storage key resolving outside root: {} -> {}", storageKey, candidate);
            throw new InvalidUploadException("Invalid file name");
        }
        return candidate;
    }

    /**
     * Reduces a client-supplied name to its last path segment.
     *
     * <p>{@code cleanPath} converts {@code \} to {@code /} and collapses
     * {@code .} / {@code ..} segments; {@code getFilename} takes the last
     * segment. Together they handle a null name, a full Windows path such as
     * {@code C:\Users\x\cv.pdf}, and a {@code ../../} prefix without hand-rolled
     * string surgery.
     */
    private static String cleanBasename(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new InvalidUploadException("Uploaded file has no name");
        }
        String basename = StringUtils.getFilename(StringUtils.cleanPath(originalFilename));
        if (!StringUtils.hasText(basename)) {
            throw new InvalidUploadException("Uploaded file has no name");
        }
        return basename;
    }

    private String allowedExtensionOf(String basename) {
        String extension = StringUtils.getFilenameExtension(basename);
        if (!StringUtils.hasText(extension)) {
            throw new InvalidUploadException(
                    "File must have one of these extensions: " + allowedExtensionsForMessage);
        }

        // Locale.ROOT, not the JVM default. Under a Turkish default locale
        // "TIF".toLowerCase() is "tif" with a dotless i, which misses the
        // allowlist. The allowlist itself is folded the same way in
        // StorageProperties.
        String normalized = extension.toLowerCase(Locale.ROOT);
        if (!allowedExtensions.contains(normalized)) {
            throw new InvalidUploadException(
                    "Unsupported file type. Allowed extensions: " + allowedExtensionsForMessage);
        }
        return normalized;
    }
}
