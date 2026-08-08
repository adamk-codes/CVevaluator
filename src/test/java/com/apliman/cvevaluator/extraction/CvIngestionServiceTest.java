package com.apliman.cvevaluator.extraction;

import com.apliman.cvevaluator.application.ApplicationService;
import com.apliman.cvevaluator.redaction.PiiRedactor;
import com.apliman.cvevaluator.storage.StorageException;
import com.apliman.cvevaluator.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CvIngestionServiceTest {

    private static final long APPLICATION_ID = 42L;
    private static final String PDF_KEY = "a-uuid.pdf";

    private StorageService storageService;
    private CvTextExtractor extractor;
    private ApplicationService applicationService;
    private CvIngestionService ingestion;

    @BeforeEach
    void setUp() {
        storageService = mock(StorageService.class);
        extractor = mock(CvTextExtractor.class);
        applicationService = mock(ApplicationService.class);
        ingestion = new CvIngestionService(
                storageService,
                extractor,
                new PiiRedactor(),
                applicationService,
                new ExtractionProperties(100));
    }

    /**
     * The scanned-PDF requirement, stated as behaviour: a document with no text
     * layer is a FAILED row with a reason a human can act on. It is emphatically
     * not an exception - this runs on a background thread where an exception
     * means the row silently stays in PROCESSING and the candidate polls
     * forever.
     */
    @Test
    void pdfWithNoTextLayerFailsWithAReadableReasonRatherThanThrowing() {
        givenExtractedText(PDF_KEY, "", CvTextExtractor.PDFBOX);

        assertDoesNotThrow(() -> ingestion.ingest(APPLICATION_ID, PDF_KEY));

        String reason = capturedFailureReason();
        assertTrue(reason.toLowerCase().contains("scan"), reason);
        verify(applicationService, never()).recordExtraction(anyLong(), anyString(), anyString(), anyString());
    }

    /**
     * A scan does not always yield exactly zero characters - stray marks and
     * page furniture produce a few. A pure isBlank() check would call that a
     * success and hand three characters to the model as a CV.
     */
    @Test
    void textBelowTheMinimumAlsoFails() {
        givenExtractedText(PDF_KEY, "Page 1 of 2", CvTextExtractor.PDFBOX);

        ingestion.ingest(APPLICATION_ID, PDF_KEY);

        String reason = capturedFailureReason();
        assertTrue(reason.contains("11"), reason);
        verify(applicationService, never()).recordExtraction(anyLong(), anyString(), anyString(), anyString());
    }

    /**
     * The headline requirement: the raw text keeps the phone number, the
     * redacted copy does not, and both are stored.
     */
    @Test
    void storesRawTextAndARedactedCopy() {
        String cv = "Adam Al Khatib, backend engineer in Beirut. Reach me on 03 123 456 "
                + "or adam@example.com. Four years of Java, Spring Boot and PostgreSQL.";
        givenExtractedText(PDF_KEY, cv, CvTextExtractor.PDFBOX);

        ingestion.ingest(APPLICATION_ID, PDF_KEY);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> redacted = ArgumentCaptor.forClass(String.class);
        verify(applicationService).recordExtraction(
                eq(APPLICATION_ID), raw.capture(), redacted.capture(), eq(CvTextExtractor.PDFBOX));

        assertEquals(cv, raw.getValue());
        assertTrue(raw.getValue().contains("03 123 456"), raw.getValue());

        assertFalse(redacted.getValue().contains("03 123 456"), redacted.getValue());
        assertFalse(redacted.getValue().contains("adam@example.com"), redacted.getValue());
        assertTrue(redacted.getValue().contains("Adam Al Khatib"), redacted.getValue());

        verify(applicationService, never()).recordExtractionFailure(anyLong(), anyString());
    }

    /**
     * PROCESSING has to be written before the work starts, not alongside the
     * result, or it is a status no polling client ever sees.
     */
    @Test
    void marksProcessingBeforeDoingTheWork() {
        givenExtractedText(PDF_KEY, "x".repeat(500), CvTextExtractor.PDFBOX);

        ingestion.ingest(APPLICATION_ID, PDF_KEY);

        InOrder inOrder = inOrder(applicationService, storageService);
        inOrder.verify(applicationService).markProcessing(APPLICATION_ID);
        inOrder.verify(storageService).load(PDF_KEY);
    }

    /**
     * TextExtractionException messages are authored for candidates, so they are
     * repeated verbatim. This is the contract that class's javadoc describes.
     */
    @Test
    void passesAnExtractionMessageThroughUnchanged() {
        when(storageService.load(PDF_KEY)).thenReturn(Path.of(PDF_KEY));
        when(extractor.extract(any(), eq("pdf")))
                .thenThrow(new TextExtractionException("This PDF is password-protected."));

        assertDoesNotThrow(() -> ingestion.ingest(APPLICATION_ID, PDF_KEY));

        assertEquals("This PDF is password-protected.", capturedFailureReason());
    }

    /**
     * The other half of that contract. StorageException carries the absolute
     * storage root, and failureReason is shown to the candidate, so the message
     * must be replaced rather than repeated.
     */
    @Test
    void doesNotLeakAServerPathFromAStorageFailure() {
        when(storageService.load(PDF_KEY))
                .thenThrow(new StorageException("Stored file is missing or unreadable: "
                        + "C:\\Users\\Adam\\Documents\\cv-uploads\\a-uuid.pdf"));

        assertDoesNotThrow(() -> ingestion.ingest(APPLICATION_ID, PDF_KEY));

        String reason = capturedFailureReason();
        assertFalse(reason.contains("C:\\"), reason);
        assertFalse(reason.contains("cv-uploads"), reason);
    }

    @Test
    void routesByTheExtensionOfTheStorageKey() {
        givenExtractedText("a-uuid.docx", "x".repeat(500), CvTextExtractor.POI_XWPF);

        ingestion.ingest(APPLICATION_ID, "a-uuid.docx");

        verify(extractor).extract(any(), eq("docx"));
    }

    private void givenExtractedText(String storageKey, String text, String method) {
        when(storageService.load(storageKey)).thenReturn(Path.of(storageKey));
        when(extractor.extract(any(), anyString())).thenReturn(new ExtractedText(text, method));
    }

    private String capturedFailureReason() {
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(applicationService).recordExtractionFailure(eq(APPLICATION_ID), reason.capture());
        return reason.getValue();
    }
}
