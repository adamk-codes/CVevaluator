package com.apliman.cvevaluator.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides whether a file's leading bytes are consistent with the extension it
 * claims. Runs before the upload is written, so a {@code .exe} renamed to
 * {@code .pdf} is a 400 rather than a file on disk that later stages have to
 * defend against.
 *
 * <p>The extension is still part of the decision, not a thing this class
 * replaces. For DOCX it is the <em>only</em> part that carries information:
 * DOCX, XLSX, PPTX and every other zip archive begin with the same four bytes.
 * The pair (extension says docx, bytes say zip) is what is accepted here. An
 * XLSX renamed to DOCX therefore passes — proving otherwise means opening the
 * archive and looking for a {@code word/document.xml} entry, which is out of
 * scope. What this gate does prove is that the file is not a renamed
 * executable.
 *
 * <p>TXT has no signature at all — that is the nature of plain text — so the
 * rule for it is a plausibility scan rather than a byte comparison.
 */
@Component
public class FileSignatureValidator {

    private static final Logger log = LoggerFactory.getLogger(FileSignatureValidator.class);

    /**
     * How many leading bytes the caller must hand over. Only 5 are needed for a
     * signature comparison; the rest exist for the TXT scan, which gets more
     * confident the more bytes it sees. Also the mark/reset readlimit in
     * {@link FileSystemStorageService}, so it must stay small enough to buffer
     * without thought.
     */
    static final int HEADER_BYTES = 512;

    /** {@code %PDF-}. */
    private static final byte[] PDF = { 0x25, 0x50, 0x44, 0x46, 0x2D };

    /**
     * {@code PK\x03\x04}, a zip local file header. Deliberately not accepting
     * {@code PK\x05\x06} (empty archive) or {@code PK\x07\x08} (spanned): both
     * are structurally zips, neither can be a DOCX.
     */
    private static final byte[] ZIP_LOCAL_FILE_HEADER = { 0x50, 0x4B, 0x03, 0x04 };

    /**
     * UTF-8, UTF-16 LE/BE, UTF-32 LE/BE. Order is irrelevant because a match on
     * any of them ends the check — this is not trying to identify the encoding,
     * only to notice that the file announced one.
     */
    private static final List<byte[]> TEXT_BYTE_ORDER_MARKS = List.of(
            new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF },
            new byte[] { (byte) 0xFF, (byte) 0xFE },
            new byte[] { (byte) 0xFE, (byte) 0xFF },
            new byte[] { (byte) 0xFF, (byte) 0xFE, 0x00, 0x00 },
            new byte[] { 0x00, 0x00, (byte) 0xFE, (byte) 0xFF }
    );

    private static final Map<String, HeaderRule> RULES = Map.of(
            "pdf", header -> startsWith(header, PDF),
            "docx", header -> startsWith(header, ZIP_LOCAL_FILE_HEADER),
            "txt", FileSignatureValidator::looksLikeText
    );

    /**
     * Fails the context if the configured allowlist contains an extension with
     * no rule.
     *
     * <p>Fail closed and fail early. The alternative — waving through any
     * extension this class does not know about — means adding {@code jpg} to
     * {@code cvevaluator.storage.allowed-extensions} silently disables content
     * checking for it, and nothing says so until someone reads this file.
     */
    public FileSignatureValidator(StorageProperties properties) {
        Set<String> unknown = new TreeSet<>(properties.allowedExtensions());
        unknown.removeAll(RULES.keySet());
        if (!unknown.isEmpty()) {
            throw new StorageException(
                    "No file signature rule for allowed extension(s): " + String.join(", ", unknown)
                            + ". Add a rule in FileSignatureValidator or remove them from "
                            + "cvevaluator.storage.allowed-extensions.");
        }
        log.info("File signature rules active for: {}", new TreeSet<>(RULES.keySet()));
    }

    /**
     * @param extension the already-normalised, already-allowlisted extension
     * @param header    the file's leading bytes, up to {@link #HEADER_BYTES};
     *                  shorter is fine and is judged on its merits, so a
     *                  three-byte "PDF" is rejected rather than crashing
     * @throws InvalidUploadException if the bytes do not match the extension
     */
    public void validate(String extension, byte[] header) {
        if (header == null || header.length == 0) {
            throw new InvalidUploadException("Uploaded file is empty");
        }

        HeaderRule rule = RULES.get(extension);
        if (rule == null) {
            // Unreachable while the constructor check holds, because the caller
            // only passes allowlisted extensions. Kept so a future caller that
            // skips the allowlist fails closed rather than silently.
            throw new InvalidUploadException("Unsupported file type: ." + extension);
        }

        if (!rule.matches(header)) {
            // The first bytes go to the log, not to the client: the client
            // already knows what it sent, and echoing them back is a free
            // oracle for probing the rules.
            log.warn("Rejected .{} upload whose content does not match; first bytes: {}",
                    extension, hexPreview(header));
            throw new InvalidUploadException(
                    "File content does not match its ." + extension + " extension");
        }
    }

    /**
     * Plain text has no magic bytes, so this asks the only question that can be
     * asked: does this look like text, or like a binary that was renamed?
     *
     * <p>A byte order mark ends it immediately. That branch is load-bearing
     * rather than a nicety — UTF-16 encodes ASCII as {@code 00}-padded pairs,
     * so genuine UTF-16 text is full of NUL bytes and the scan below would
     * reject it.
     *
     * <p>Without a BOM the file is assumed to be UTF-8 or some 8-bit encoding,
     * and anything from the C0 control range that is not whitespace counts
     * against it. That is what catches a renamed executable: {@code MZ} is two
     * perfectly printable characters, but the bytes behind it are not.
     * High bytes are left alone — they are UTF-8 continuation bytes or
     * Latin-1 accents, both of which belong in a CV.
     */
    private static boolean looksLikeText(byte[] header) {
        for (byte[] bom : TEXT_BYTE_ORDER_MARKS) {
            if (startsWith(header, bom)) {
                return true;
            }
        }

        for (byte b : header) {
            int value = b & 0xFF;
            boolean printable = value >= 0x20;
            boolean allowedWhitespace = value == '\t' || value == '\n' || value == '\r' || value == 0x0C;
            if (!printable && !allowedWhitespace) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] header, byte[] signature) {
        if (header.length < signature.length) {
            return false;
        }
        return Arrays.equals(header, 0, signature.length, signature, 0, signature.length);
    }

    private static String hexPreview(byte[] header) {
        int length = Math.min(header.length, 8);
        StringBuilder hex = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            hex.append(String.format("%02X ", header[i]));
        }
        return hex.toString().trim();
    }

    @FunctionalInterface
    private interface HeaderRule {
        boolean matches(byte[] header);
    }
}
