package com.apliman.cvevaluator.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemStorageServiceTest {

    @TempDir
    Path tempDir;

    private Path root;
    private FileSystemStorageService service;

    @BeforeEach
    void setUp() {
        // A named subdirectory, not tempDir itself, so the sibling-prefix test
        // below has somewhere outside the root to try to reach.
        root = tempDir.resolve("uploads");
        service = serviceWith(root, Set.of("pdf", "docx"));
    }

    private static FileSystemStorageService serviceWith(Path location, Set<String> extensions) {
        return new FileSystemStorageService(new StorageProperties(location, extensions));
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
