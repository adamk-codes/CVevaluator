package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.job.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByJobOrderBySubmittedAt(Job job);

    /**
     * How many CVs are attached to a job. Derived query, so it is one
     * {@code SELECT COUNT} — the reason it exists rather than callers taking
     * {@code findByJobOrderBySubmittedAt(job).size()} and loading every row and
     * its extracted text to count them.
     */
    long countByJob(Job job);
}
