package com.apliman.cvevaluator.job.dto;

public record CreateJobRequest(
        String title,
        String description,
        String requirements,
        String seniority
) {}