package com.apliman.cvevaluator.application.dto;

import com.apliman.cvevaluator.application.ApplicationStatus;

import java.time.Instant;

/**
 * Where one CV has got to in the pipeline.
 *
 * <p>The resource a client polls after the 202. Distinct from
 * {@link ApplicationResponse} rather than an extension of it: that record is
 * the submission receipt and is written once, while this one is read
 * repeatedly and has to answer "why did it stop?" as well as "did it stop?".
 * Merging them would put {@code failureReason} on a body returned at a moment
 * when it is always null.
 *
 * <p>Carries no {@code extractedText}. The text is the largest column on the
 * row and no screen shows it — see the projection query in
 * {@code ApplicationRepository} that exists to avoid loading it at all.
 *
 * <p>{@code failureReason} is null unless {@code status} is
 * {@link ApplicationStatus#FAILED}. It is authored by the extraction pipeline,
 * never a raw exception message — a scanned PDF with no text layer is the
 * expected case here and the candidate is meant to be able to read why.
 *
 * @param textLength       characters extracted, 0 until extraction completes
 * @param extractionMethod which parser produced the text, null until then
 */
public record ApplicationStatusResponse(
        Long id,
        Long jobId,
        String originalFilename,
        ApplicationStatus status,
        String failureReason,
        long sizeBytes,
        int textLength,
        String extractionMethod,
        Instant submittedAt
) {
}
