package com.apliman.cvevaluator.job.dto;

import com.apliman.cvevaluator.job.JobRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param requirements the authored list. Carries no Bean Validation annotations
 *                     on purpose: every rule about it, down to "there must be at
 *                     least one", lives in {@code JobRequirementsValidator} so
 *                     that this endpoint and
 *                     {@code PUT /api/jobs/{id}/requirements} cannot drift
 *                     apart. A missing field arrives here as {@code null} and is
 *                     rejected there.
 */
public record CreateJobRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @NotBlank @Size(max = 50) String seniority,
        List<JobRequirement> requirements
) {}
