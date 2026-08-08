package com.apliman.cvevaluator.extraction;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every fixture here is generated at test time rather than committed as a
 * binary. Two reasons: a checked-in PDF is a blob nobody can review in a diff,
 * and generating them means the DOCX is written by the same POI version that
 * reads it, so the test cannot rot into passing against a stale file.
 */
class CvTextExtractorTest {

    private final CvTextExtractor extractor = new CvTextExtractor();

    @TempDir
    Path tempDir;

    @Test
    void readsAPdfWithATextLayer() throws IOException {
        Path file = pdfContaining("Adam Al Khatib", "Backend engineer");

        ExtractedText extracted = extractor.extract(file, "pdf");

        assertEquals(CvTextExtractor.PDFBOX, extracted.method());
        assertTrue(extracted.text().contains("Adam Al Khatib"), extracted.text());
        assertTrue(extracted.text().contains("Backend engineer"), extracted.text());
    }

    /**
     * The scanned-CV case, reduced to its essence: a page that exists and draws
     * nothing. That is byte-for-byte the same situation PDFBox faces with an
     * image-only scan - a valid document whose pages carry no text operators -
     * so it exercises the same branch without committing a multi-megabyte scan
     * to the repository.
     *
     * <p>Note what this asserts: blank, <em>not</em> an exception. Turning "no
     * text layer" into a failure is the ingestion service's job, and keeping it
     * out of here is what lets that policy be tested without a database.
     */
    @Test
    void returnsBlankForAPdfWithNoTextLayer() throws IOException {
        Path file = tempDir.resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(file.toFile());
        }

        ExtractedText extracted = extractor.extract(file, "pdf");

        assertEquals("", extracted.text().strip());
    }

    @Test
    void readsADocx() throws IOException {
        Path file = tempDir.resolve("cv.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream out = Files.newOutputStream(file)) {
            document.createParagraph().createRun().setText("Adam Al Khatib");
            document.createParagraph().createRun().setText("03 123 456");
            document.write(out);
        }

        ExtractedText extracted = extractor.extract(file, "docx");

        assertEquals(CvTextExtractor.POI_XWPF, extracted.method());
        assertTrue(extracted.text().contains("Adam Al Khatib"), extracted.text());
        assertTrue(extracted.text().contains("03 123 456"), extracted.text());
    }

    /**
     * The hole FileSignatureValidator documents: every Office file is a zip
     * starting with the same four bytes, so it cannot tell a DOCX from anything
     * else zip-shaped, and something unreadable does reach the extractor. POI
     * signals that with an unchecked exception, which is why readDocx catches
     * RuntimeException as well as IOException.
     */
    @Test
    void unreadableDocxFailsWithAReadableMessage() throws IOException {
        Path file = tempDir.resolve("not-really.docx");
        Files.write(file, new byte[] { 0x50, 0x4B, 0x03, 0x04, 0x00, 0x01, 0x02, 0x03 });

        TextExtractionException thrown =
                assertThrows(TextExtractionException.class, () -> extractor.extract(file, "docx"));

        assertTrue(thrown.getMessage().contains("DOCX"), thrown.getMessage());
        // The message goes straight into failureReason and is shown to the
        // candidate, so it must not carry a server path.
        assertTrue(thrown.getMessage().indexOf(tempDir.toString()) < 0, thrown.getMessage());
    }

    @Test
    void readsPlainTextAsUtf8() throws IOException {
        Path file = tempDir.resolve("cv.txt");
        Files.writeString(file, "Adam Al Khatib — Beyrouth", StandardCharsets.UTF_8);

        ExtractedText extracted = extractor.extract(file, "txt");

        assertEquals(CvTextExtractor.PLAIN_TEXT, extracted.method());
        assertEquals("Adam Al Khatib — Beyrouth", extracted.text());
    }

    /**
     * The case that justifies the BOM sniff. FileSignatureValidator accepts
     * UTF-16 precisely because it announces itself, so such a file reaches here.
     * Decoded as UTF-8 it would not throw and would not be blank - it would be a
     * string of replacement characters long enough to pass the minimum-length
     * check and be sent to the model as noise. Silent corruption, not a crash.
     */
    @Test
    void readsUtf16WithAByteOrderMark() throws IOException {
        Path file = tempDir.resolve("cv.txt");
        byte[] bom = { (byte) 0xFF, (byte) 0xFE };
        byte[] body = "Adam Al Khatib".getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(body, 0, withBom, bom.length, body.length);
        Files.write(file, withBom);

        ExtractedText extracted = extractor.extract(file, "txt");

        assertEquals("Adam Al Khatib", extracted.text());
    }

    @Test
    void stripsTheUtf8ByteOrderMark() throws IOException {
        Path file = tempDir.resolve("cv.txt");
        Files.write(file, concat(
                new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF },
                "Adam Al Khatib".getBytes(StandardCharsets.UTF_8)));

        ExtractedText extracted = extractor.extract(file, "txt");

        // Without the strip this is "﻿Adam Al Khatib" - invisible in a
        // console, and a leading garbage character in every prompt.
        assertEquals("Adam Al Khatib", extracted.text());
    }

    @Test
    void rejectsAnExtensionItHasNoReaderFor() {
        Path file = tempDir.resolve("cv.rtf");

        assertThrows(TextExtractionException.class, () -> extractor.extract(file, "rtf"));
    }

    private Path pdfContaining(String... lines) throws IOException {
        Path file = tempDir.resolve("cv.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                // PDFBox 3 removed the PDType1Font.HELVETICA constant; the
                // standard 14 fonts are constructed from the enum now.
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(16f);
                content.newLineAtOffset(50, 750);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(file.toFile());
        }
        return file;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
