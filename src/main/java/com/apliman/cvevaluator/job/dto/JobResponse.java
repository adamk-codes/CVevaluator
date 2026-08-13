package com.apliman.cvevaluator.job.dto;

import com.apliman.cvevaluator.job.Job;
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

    /**
     * One mapping, shared by every endpoint that returns a job.
     *
     * <p>Added when a second controller started returning jobs. Two hand-rolled
     * mappings of the same entity are two places to remember when a field is
     * added, and the one that gets forgotten is the one nobody is looking at.
     */
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getTitle(),
                job.getDescription(),
                job.getSeniority(),
                job.getRequirements(),
                job.getRequirementsVersion(),
                job.getCreatedAt(),
                job.isActive());
    }
}
