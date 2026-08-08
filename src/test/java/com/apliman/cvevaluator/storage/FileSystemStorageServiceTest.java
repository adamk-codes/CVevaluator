package com.apliman.cvevaluator.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FileSystemStorageServiceTest {

    @TempDir
    Path tempDir;

    private Path root;
    private FileSystemStorageService service;

    private FileSignatureValidator validator;

    @BeforeEach
    void setUp() {
        // A named subdirectory, not tempDir itself, so the sibling-prefix test
        // below has somewhere outside the root to try to reach.
        root = tempDir.resolve("uploads");
        validator = mock(FileSignatureValidator.class);
        service = serviceWith(root, Set.of("pdf", "docx"), validator);
    }

    /**
     * The default validator here is a permissive mock, so the fixtures below can
     * stay readable strings instead of hand-built file headers. What the rules
     * actually say is FileSignatureValidatorTest's job; this class is about
     * names, paths and the allowlist. The two tests that need the real rules ask
     * for them explicitly.
     */
    private FileSystemStorageService serviceWith(Path location, Set<String> extensions) {
        return serviceWith(location, extensions, validator);
    }

    private static FileSystemStorageService serviceWith(
            Path location, Set<String> extensions, FileSignatureValidator validator) {
        return new FileSystemStorageService(new StorageProperties(location, extensions), validator);
    }

    private static MockMultipartFile upload(String filename, String content) {
        return new MockMultipartFile("file", filename, "application/octet-stream", content.getBytes());
    }

    private List<Path> filesInRoot() throws IOException {
        try (var entries = Files.list(root)) {
            return entries.toList();
        }
    }

    @Test
    @DisplayName("creates the storage root at construction")
    void createsRoot() {
        assertTrue(Files.isDirectory(root));
    }

    // --- generated names ---------------------------------------------------

    @Test
    @DisplayName("stores under a UUID filename, never the client's name")
    void storesUnderUuidName() throws IOException {
        StoredFile stored = service.store(upload("Adam Alkhatib CV.pdf", "hello"));

        List<Path> files = filesInRoot();
        assertEquals(1, files.size());

        String onDisk = files.get(0).getFileName().toString();
        assertEquals(stored.storageKey(), onDisk);
        assertTrue(onDisk.endsWith(".pdf"));
        assertFalse(onDisk.contains("Adam"), "client-supplied name leaked onto disk: " + onDisk);

        String withoutExtension = onDisk.substring(0, onDisk.lastIndexOf('.'));
        assertDoesNotThrow(() -> UUID.fromString(withoutExtension), "not a UUID: " + withoutExtension);

        // the original name survives as a display value only
        assertEquals("Adam Alkhatib CV.pdf", stored.originalFilename());
        assertEquals("pdf", stored.extension());
        assertEquals(5, stored.sizeBytes());
    }

    @Test
    @DisplayName("two uploads of the same filename do not collide")
    void sameNameDoesNotCollide() throws IOException {
        StoredFile first = service.store(upload("cv.pdf", "one"));
        StoredFile second = service.store(upload("cv.pdf", "two"));

        assertFalse(first.storageKey().equals(second.storageKey()));
        assertEquals(2, filesInRoot().size());
        assertEquals("one", Files.readString(service.load(first.storageKey())));
        assertEquals("two", Files.readString(service.load(second.storageKey())));
    }

    @Test
    @DisplayName("storageKey round-trips through load with the bytes intact")
    void roundTrip() throws IOException {
        StoredFile stored = service.store(upload("cv.docx", "some bytes"));

        Path loaded = service.load(stored.storageKey());

        assertEquals("some bytes", Files.readString(loaded));
        assertTrue(loaded.isAbsolute());
    }

    @Test
    @DisplayName("load of an unknown key fails as a storage error, not silently")
    void loadMissingFile() {
        assertThrows(StorageException.class, () -> service.load(UUID.randomUUID() + ".pdf"));
    }

    // --- extension allowlist ------------------------------------------------

    @Test
    @DisplayName("uppercase extension is accepted and normalised to lower case")
    void uppercaseExtensionAccepted() throws IOException {
        StoredFile stored = service.store(upload("CV.PDF", "hello"));

        assertEquals("pdf", stored.extension());
        assertTrue(stored.storageKey().endsWith(".pdf"));
        assertTrue(filesInRoot().get(0).getFileName().toString().endsWith(".pdf"));
    }

    @Test
    @DisplayName("disallowed extension is rejected")
    void disallowedExtensionRejected() throws IOException {
        assertThrows(InvalidUploadException.class, () -> service.store(upload("payload.exe", "MZ")));
        assertTrue(filesInRoot().isEmpty(), "rejected upload still wrote to disk");
    }

    @Test
    @DisplayName("double extension is judged on the last one only")
    void doubleExtensionJudgedOnLast() {
        assertThrows(InvalidUploadException.class, () -> service.store(upload("cv.pdf.exe", "MZ")));
        assertDoesNotThrow(() -> service.store(upload("cv.exe.pdf", "harmless")));
    }

    @Test
    @DisplayName("missing or empty extension is rejected")
    void missingExtensionRejected() {
        assertThrows(InvalidUploadException.class, () -> service.store(upload("cv", "hello")));
        assertThrows(InvalidUploadException.class, () -> service.store(upload("cv.", "hello")));
    }

    @Test
    @DisplayName("empty file is rejected")
    void emptyFileRejected() {
        MockMultipartFile empty = new MockMultipartFile("file", "cv.pdf", "application/pdf", new byte[0]);

        assertThrows(InvalidUploadException.class, () -> service.store(empty));
    }

    @Test
    @DisplayName("upload with no filename is rejected")
    void namelessFileRejected() {
        assertThrows(InvalidUploadException.class, () -> service.store(upload("", "hello")));
    }

    @Test
    @DisplayName("an allowlist configured in upper case still matches")
    void configuredAllowlistIsFolded() {
        // allowed-extensions=PDF must not silently reject everything
        FileSystemStorageService shouty = serviceWith(tempDir.resolve("shouty"), Set.of("PDF"));

        assertDoesNotThrow(() -> shouty.store(upload("cv.pdf", "hello")));
    }

    // --- content check ------------------------------------------------------

    @Nested
    @DisplayName("magic-byte check")
    class ContentCheck {

        /** The first bytes of a real PDF. */
        private static final byte[] PDF_HEADER = "%PDF-1.7\n%âãÏÓ\n"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        /** "MZ" and a DOS header full of zeroes - a Windows executable. */
        private static byte[] executable() {
            byte[] bytes = new byte[64];
            bytes[0] = 'M';
            bytes[1] = 'Z';
            bytes[2] = (byte) 0x90;
            return bytes;
        }

        private FileSystemStorageService withRealValidator() {
            Set<String> extensions = Set.of("pdf", "docx", "txt");
            return serviceWith(root, extensions,
                    new FileSignatureValidator(new StorageProperties(root, extensions)));
        }

        @Test
        @DisplayName("the file's leading bytes are handed to the validator with its extension")
        void validatorSeesTheHeader() {
            service.store(new MockMultipartFile("file", "cv.pdf", "application/pdf", PDF_HEADER));

            ArgumentCaptor<byte[]> header = ArgumentCaptor.forClass(byte[].class);
            verify(validator).validate(eq("pdf"), header.capture());
            assertArrayEquals(PDF_HEADER, header.getValue());
        }

        /**
         * The ordering that makes the whole design work. The validator runs
         * against a marked stream that is then reset, so a rejection happens
         * before Files.copy - there is no partial file and nothing to clean up.
         */
        @Test
        @DisplayName("a rejected upload writes nothing to disk")
        void rejectionLeavesNoFile() throws IOException {
            doThrow(new InvalidUploadException("nope")).when(validator).validate(any(), any());

            assertThrows(InvalidUploadException.class, () -> service.store(upload("cv.pdf", "whatever")));
            assertTrue(filesInRoot().isEmpty(), "rejected upload still wrote to disk");
        }

        @Test
        @DisplayName("an executable renamed to .pdf is rejected and leaves no file")
        void renamedExecutableRejected() throws IOException {
            FileSystemStorageService real = withRealValidator();
            MockMultipartFile disguised =
                    new MockMultipartFile("file", "cv.pdf", "application/pdf", executable());

            assertThrows(InvalidUploadException.class, () -> real.store(disguised));
            assertTrue(filesInRoot().isEmpty(), "rejected upload still wrote to disk");
        }

        /**
         * The other half of that test: the real rules must not break the happy
         * path. The bytes are read twice - once to check, once to copy - and
         * this is what proves the reset() put the stream back where it started.
         */
        @Test
        @DisplayName("a real PDF still stores, with every byte intact after the header is read")
        void realPdfStillStores() throws IOException {
            FileSystemStorageService real = withRealValidator();
            byte[] content = new byte[PDF_HEADER.length + 2000];
            System.arraycopy(PDF_HEADER, 0, content, 0, PDF_HEADER.length);
            java.util.Arrays.fill(content, PDF_HEADER.length, content.length, (byte) 'x');

            StoredFile stored =
                    real.store(new MockMultipartFile("file", "cv.pdf", "application/pdf", content));

            // Longer than the 512-byte mark readlimit on purpose: if reset()
            // were dropping the buffered prefix, or the copy were starting after
            // the header, this comparison is where it would show.
            assertArrayEquals(content, Files.readAllBytes(real.load(stored.storageKey())));
        }
    }

    // --- path traversal -----------------------------------------------------

    @Nested
    @DisplayName("path traversal")
    class Traversal {

        @Test
        @DisplayName("a ../ prefix in the client's filename cannot escape the root")
        void traversalInOriginalFilenameNeutralised() throws IOException {
            StoredFile stored = service.store(upload("../../../evil.pdf", "hello"));

            List<Path> files = filesInRoot();
            assertEquals(1, files.size());
            assertEquals(stored.storageKey(), files.get(0).getFileName().toString());
            assertFalse(Files.exists(tempDir.resolve("evil.pdf")));
            assertFalse(Files.exists(tempDir.getParent().resolve("evil.pdf")));
        }

        @Test
        @DisplayName("a Windows-style absolute filename cannot escape the root")
        void windowsPathInOriginalFilenameNeutralised() throws IOException {
            StoredFile stored = service.store(upload("C:\\Windows\\Temp\\evil.pdf", "hello"));

            assertEquals(1, filesInRoot().size());
            assertEquals("evil.pdf", stored.originalFilename());
            assertTrue(service.load(stored.storageKey()).startsWith(root));
        }

        @Test
        @DisplayName("load rejects a key that climbs out of the root")
        void loadRejectsDotDot() {
            assertThrows(InvalidUploadException.class, () -> service.load("../evil.pdf"));
            assertThrows(InvalidUploadException.class, () -> service.load("sub/../../evil.pdf"));
        }

        @Test
        @DisplayName("load rejects an absolute key")
        void loadRejectsAbsoluteKey() {
            assertThrows(InvalidUploadException.class, () -> service.load("/etc/passwd"));
            assertThrows(InvalidUploadException.class,
                    () -> service.load(tempDir.resolve("evil.pdf").toAbsolutePath().toString()));
        }

        @Test
        @DisplayName("load rejects a blank key")
        void loadRejectsBlankKey() {
            assertThrows(InvalidUploadException.class, () -> service.load(null));
            assertThrows(InvalidUploadException.class, () -> service.load("   "));
        }

        /**
         * The test that justifies Path.startsWith over String.startsWith.
         *
         * <p>The root is ".../uploads". This key resolves to ".../uploads-evil/x.pdf",
         * which is a character-prefix match on the root but is NOT inside it.
         * A String.startsWith implementation lets this through; Path.startsWith
         * rejects it, because it compares name elements and "uploads-evil" is not
         * the element "uploads".
         */
        @Test
        @DisplayName("load rejects a sibling directory that shares the root's name prefix")
        void loadRejectsSiblingPrefixDirectory() {
            String siblingKey = "../" + root.getFileName() + "-evil/x.pdf";

            Path escaped = root.resolve(siblingKey).normalize();
            assertTrue(escaped.toString().startsWith(root.toString()),
                    "precondition: this key must fool a String prefix check");
            assertFalse(escaped.startsWith(root),
                    "precondition: this key must not fool a Path prefix check");

            assertThrows(InvalidUploadException.class, () -> service.load(siblingKey));
        }
    }

    // --- locale-independent case folding ------------------------------------

    @Nested
    @DisplayName("Locale.ROOT case folding")
    class LocaleFolding {

        private Locale original;

        @BeforeEach
        void turkishDefaultLocale() {
            original = Locale.getDefault();
            Locale.setDefault(Locale.forLanguageTag("tr"));
        }

        @AfterEach
        void restoreLocale() {
            Locale.setDefault(original);
        }

        /**
         * Under a Turkish default locale "TIF".toLowerCase() is "tif" with a
         * dotless i (U+0131), which misses a "tif" allowlist entry. Only
         * toLowerCase(Locale.ROOT) folds it to ASCII "tif".
         *
         * <p>"tif" is not in the real allowlist — pdf and docx contain no 'I', so
         * the bug is not observable with production config. It is injected here to
         * make the difference a real assertion rather than a comment.
         */
        @Test
        @DisplayName("an uppercase 'I' in the extension folds to ASCII, not dotless i")
        void turkishLocaleDoesNotBreakAllowlist() {
            assertEquals("t\u0131f", "TIF".toLowerCase(),
                    "precondition: default locale must mis-fold I");

            FileSystemStorageService withTif = serviceWith(tempDir.resolve("images"), Set.of("tif"));

            StoredFile stored = assertDoesNotThrow(() -> withTif.store(upload("photo.TIF", "bytes")));
            assertEquals("tif", stored.extension());
        }
    }
}
