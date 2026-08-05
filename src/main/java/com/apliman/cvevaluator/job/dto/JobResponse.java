package com.apliman.cvevaluator.job.dto;

import java.time.Instant;

public record JobResponse(
        Long id,
        String title,
        String description,
        String requirements,
        String seniority,
        Instant createdAt,
        boolean active
) {
}
