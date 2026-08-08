package com.apliman.cvevaluator.job.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateJobRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @Size(max = 5000) String requirements,
        @NotBlank @Size(max = 50) String seniority
) {}