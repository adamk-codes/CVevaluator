package com.apliman.cvevaluator.job.dto;

import com.apliman.cvevaluator.job.JobRequirement;

import java.util.List;

/**
 * Body of {@code PUT /api/jobs/{id}/requirements}.
 *
 * <p>A record wrapping the list rather than a bare {@code List<JobRequirement>}
 * request body. Same reason the project wraps lists for structured LLM output:
 * a top-level JSON array is a shape with nowhere to grow, and the first time
 * this needs a sibling field — a reason for the change, say — a bare array is a
 * breaking change and this is not.
 */
public record UpdateJobRequirementsRequest(
        List<JobRequirement> requirements
) {}
