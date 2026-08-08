package com.apliman.cvevaluator.extraction;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Turns a stored CV file into plain text.
 *
 * <p>A pure function over a file: no database, no transaction, no Spring
 * machinery beyond being a bean. It reports what it found and nothing more —
 * including when what it found is nothing. A blank return is a legitimate
 * answer, not an error, because "this PDF has no text layer" is a fact about
 * the file rather than a failure to read it. {@link CvIngestionService} owns
 * the policy of what too little text means.
 *
 * <p>A single switch over three formats, rather than a
 * {@code Map<String, DocumentReader>} of strategy beans. Three cases fit on one
 * screen and the fourth format does not exist yet; the indirection would buy
 * nothing today.
 */
@Component
public class CvTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(CvTextExtractor.class);

    public static final String PDFBOX = "PDFBOX";
    public static final String POI_XWPF = "POI_XWPF";
    public static final String PLAIN_TEXT = "PLAIN_TEXT";

    /**
     * @param file      a path already resolved and existence-checked by
     *                  {@code StorageService.load}
     * @param extension the normalised, allowlisted extension
     * @throws TextExtractionException if the file cannot be read; the message is
     *                                 safe to show the candidate
     */
    public ExtractedText extract(Path file, String extension) {
        return switch (extension) {
            case "pdf" -> new ExtractedText(readPdf(file), PDFBOX);
            case "docx" -> new ExtractedText(readDocx(file), POI_XWPF);
            case "txt" -> new ExtractedText(readPlainText(file), PLAIN_TEXT);
            // Unreachable while the storage allowlist and this switch agree.
            // Kept so that adding an extension to the allowlist without adding a
            // reader here fails loudly on the first upload instead of silently
            // storing a CV nothing will ever read.
            default -> throw new TextExtractionException(
                    "This file type cannot be read. Please upload a PDF, DOCX or TXT file.");
        };
    }

    /**
     * <p>{@code setSortByPosition(true)} is not optional for CVs. By default
     * PDFBox emits text in the order the content stream draws it, which for the
     * two-column layout every CV template uses interleaves the sidebar with the
     * body — "Skills Java 2019 Python Beirut" and so on. Sorting by position
     * costs a little time per page and is the difference between text an LLM can
     * reason about and word salad.
     *
     * <p>PDFBox 3 note: the entry point is {@code Loader.loadPDF}.
     * {@code PDDocument.load} was removed in 3.0, so every 2.x snippet online
     * will not compile here.
     */
    private static String readPdf(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (InvalidPasswordException e) {
            // Subclass of IOException, so it has to be caught first to get its
            // own message. A password-protected CV is a user mistake with an
            // obvious fix, not a server problem.
            throw new TextExtractionException(
                    "This PDF is password-protected. Please upload a version without a password.", e);
        } catch (IOException e) {
            throw new TextExtractionException(
                    "This PDF could not be read. It may be corrupt or incomplete.", e);
        }
    }

    /**
     * <p>{@code XWPFWordExtractor} rather than walking paragraphs by hand: it
     * also picks up tables, headers and footers, and CV templates put real
     * content — contact details especially — in all three.
     *
     * <p>The {@code RuntimeException} catch is load-bearing, not defensive
     * padding. {@code FileSignatureValidator} documents that it cannot tell a
     * DOCX from an XLSX, because every Office file is a zip starting with the
     * same four bytes. So a renamed spreadsheet legitimately reaches this
     * method, and POI answers that with an unchecked {@code POIXMLException},
     * not an {@code IOException}. Without this catch that would escape as a raw
     * POI message on a background thread.
     */
    private static String readDocx(Path file) {
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (IOException | RuntimeException e) {
            log.warn("POI could not read {} as a DOCX", file.getFileName(), e);
            throw new TextExtractionException(
                    "This DOCX could not be read. It may be corrupt, or it may be a different "
                            + "Office file renamed to .docx.", e);
        }
    }

    /**
     * <p>The byte order mark check is the whole reason this is not a one-line
     * {@code Files.readString}. {@code FileSignatureValidator.looksLikeText}
     * accepts UTF-16 and UTF-32 files precisely because they announce
     * themselves with a BOM, so such a file can and will arrive here. Decoding
     * UTF-16 as UTF-8 does not throw — it yields a string of replacement
     * characters that is not blank, so it would sail past the too-little-text
     * check and be handed to the LLM as garbage. Failing loudly is not an
     * option either; reading it correctly is cheap.
     *
     * <p>Order matters below: the UTF-32LE mark begins with the two bytes that
     * are the entire UTF-16LE mark, so the longer one has to be tested first.
     */
    private static String readPlainText(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new TextExtractionException("This file could not be read.", e);
        }

        for (ByteOrderMark bom : BYTE_ORDER_MARKS) {
            if (bom.matches(bytes)) {
                return new String(bytes, bom.marker.length, bytes.length - bom.marker.length, bom.charset);
            }
        }

        // No mark: assume UTF-8. This constructor substitutes U+FFFD for
        // malformed input rather than throwing, which is the behaviour we want —
        // one bad byte in a CV should not fail the whole application.
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record ByteOrderMark(byte[] marker, Charset charset) {

        boolean matches(byte[] bytes) {
            return bytes.length >= marker.length
                    && Arrays.equals(bytes, 0, marker.length, marker, 0, marker.length);
        }
    }

    private static final ByteOrderMark[] BYTE_ORDER_MARKS = {
            new ByteOrderMark(new byte[] { (byte) 0xFF, (byte) 0xFE, 0x00, 0x00 },
                    Charset.forName("UTF-32LE")),
            new ByteOrderMark(new byte[] { 0x00, 0x00, (byte) 0xFE, (byte) 0xFF },
                    Charset.forName("UTF-32BE")),
            new ByteOrderMark(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF },
                    StandardCharsets.UTF_8),
            new ByteOrderMark(new byte[] { (byte) 0xFF, (byte) 0xFE },
                    StandardCharsets.UTF_16LE),
            new ByteOrderMark(new byte[] { (byte) 0xFE, (byte) 0xFF },
                    StandardCharsets.UTF_16BE)
    };
}
