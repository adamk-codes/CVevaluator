package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.job.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByJobOrderBySubmittedAt(Job job);
}
