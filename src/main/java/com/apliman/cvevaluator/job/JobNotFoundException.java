package com.apliman.cvevaluator.job;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(Long id) {
        super("Job not found: " + id);
    }
}