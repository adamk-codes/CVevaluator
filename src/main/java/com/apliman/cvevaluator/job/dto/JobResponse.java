package com.apliman.cvevaluator.job.dto;

import com.apliman.cvevaluator.job.JobRequirement;

import java.time.Instant;
import java.util.List;

public record JobResponse(
        Long id,
        String title,
        String description,
        String seniority,
        List<JobRequirement> requirements,
        int requirementsVersion,
        Instant createdAt,
        boolean active
) {
}
