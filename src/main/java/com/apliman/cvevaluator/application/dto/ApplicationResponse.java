package com.apliman.cvevaluator.application.dto;

import com.apliman.cvevaluator.application.ApplicationStatus;

import java.time.Instant;

/**
 * The 202 body for a submitted CV.
 *
 * <p>Carries no {@code storagePath}: where the file landed on disk is a server
 * detail and the candidate has no use for it.
 */
public record ApplicationResponse(
        Long id,
        Long jobId,
        String originalFilename,
        ApplicationStatus status,
        Instant submittedAt
) {
}
