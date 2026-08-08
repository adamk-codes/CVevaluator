package com.apliman.cvevaluator.extraction;

import com.apliman.cvevaluator.application.ApplicationCreatedEvent;
import com.apliman.cvevaluator.application.ApplicationService;
import com.apliman.cvevaluator.redaction.PiiRedactor;
import com.apliman.cvevaluator.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Runs a stored CV through extraction and redaction and writes the result back
 * to the row.
 *
 * <p>This class holds the <em>policy</em> that {@link CvTextExtractor}
 * deliberately does not: what counts as too little text, what the candidate is
 * told when something goes wrong, and which exception messages are safe to
 * repeat.
 *
 * <p>It is not {@code @Transactional}, and must not become so. It does file I/O
 * — opening and parsing a PDF that may take seconds — and per project rule that
 * never happens inside a transaction. The three writes it performs are each a
 * separate short transaction on {@link ApplicationService}.
 */
@Service
public class CvIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CvIngestionService.class);

    private final StorageService storageService;
    private final CvTextExtractor extractor;
    private final PiiRedactor redactor;
    private final ApplicationService applicationService;
    private final ExtractionProperties properties;

    public CvIngestionService(
            StorageService storageService,
            CvTextExtractor extractor,
            PiiRedactor redactor,
            ApplicationService applicationService,
            ExtractionProperties properties
    ) {
        this.storageService = storageService;
        this.extractor = extractor;
        this.redactor = redactor;
        this.applicationService = applicationService;
        this.properties = properties;
    }

    /**
     * The two annotations answer two different questions and both are needed.
     *
     * <p>{@code AFTER_COMMIT} answers <em>when</em>. The event is published while
     * the insert's transaction is still open; a plain {@code @EventListener}
     * would fire inside it, and the extraction thread would then look for a row
     * that its own connection cannot see. That failure is also a race, so it
     * would pass locally and appear under load.
     *
     * <p>{@code @Async} answers <em>where</em>. Without it the listener still
     * runs after commit, but on the request thread, and the client's 202 would
     * be held open for the length of the PDF parse — which is the precise thing
     * the 202-and-poll design exists to prevent.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApplicationCreated(ApplicationCreatedEvent event) {
        ingest(event.applicationId(), event.storageKey());
    }

    /**
     * Public and callable directly so it can be tested, and re-run by hand
     * against an application that got stuck.
     */
    public void ingest(Long applicationId, String storageKey) {
        applicationService.markProcessing(applicationId);

        try {
            Path file = storageService.load(storageKey);
            ExtractedText extracted = extractor.extract(file, extensionOf(storageKey));

            String text = extracted.text() == null ? "" : extracted.text();
            int meaningfulLength = text.strip().length();

            if (meaningfulLength < properties.minimumTextLength() || meaningfulLength == 0) {
                String reason = tooLittleTextReason(storageKey, meaningfulLength);
                log.info("Application {}: {} characters of text extracted; failing as unreadable",
                        applicationId, meaningfulLength);
                applicationService.recordExtractionFailure(applicationId, reason);
                return;
            }

            applicationService.recordExtraction(applicationId, text, redactor.redact(text), extracted.method());
            log.info("Application {}: extracted {} characters via {}",
                    applicationId, text.length(), extracted.method());

        } catch (TextExtractionException e) {
            // The only exception type whose message is authored for candidates.
            // Everything else gets a generic reason and the detail goes to the log.
            log.warn("Application {}: extraction failed", applicationId, e);
            applicationService.recordExtractionFailure(applicationId, e.getMessage());

        } catch (RuntimeException e) {
            // Chiefly StorageException, whose message contains the absolute
            // storage root. Returning it would leak a server path to the
            // candidate, so it is logged and replaced.
            log.error("Application {}: unexpected failure while reading {}", applicationId, storageKey, e);
            applicationService.recordExtractionFailure(applicationId,
                    "The CV could not be read on the server. Please try uploading it again.");
        }
    }

    /**
     * The message is split by format because the advice differs. Only a PDF can
     * plausibly be a scan, and telling a candidate who uploaded a DOCX that
     * their file looks scanned would be nonsense.
     */
    private static String tooLittleTextReason(String storageKey, int length) {
        if (length == 0) {
            return "pdf".equals(extensionOf(storageKey))
                    ? "No text could be found in this PDF. It looks like a scan or an image-only "
                            + "export, and scanned CVs are not supported. Please upload a PDF with "
                            + "selectable text."
                    : "No text could be found in this file. Please check it is not empty.";
        }
        return "Only " + length + " characters of text could be read from this file, which is too "
                + "little to evaluate. If it is a scanned or image-based CV, please upload a "
                + "version with selectable text.";
    }

    /**
     * The storage key is always {@code <uuid>.<ext>} as generated by
     * {@code FileSystemStorageService}, so the extension is derivable and does
     * not need its own column. Folded with {@code Locale.ROOT} for the same
     * reason the storage allowlist is.
     */
    private static String extensionOf(String storageKey) {
        String extension = StringUtils.getFilenameExtension(storageKey);
        return extension == null ? "" : extension.toLowerCase(Locale.ROOT);
    }
}
