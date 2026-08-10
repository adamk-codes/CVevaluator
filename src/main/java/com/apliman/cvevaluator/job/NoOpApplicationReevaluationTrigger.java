package com.apliman.cvevaluator.job;

import com.apliman.cvevaluator.application.ApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only {@link ApplicationReevaluationTrigger} that exists today. It logs
 * what a real implementation would have queued and returns.
 *
 * <p>It counts the affected applications rather than logging nothing, because
 * the number is the part worth seeing before the async version is wired in: it
 * is the size of the burst that will one day hit the LLM in one go when a
 * recruiter edits a popular job's requirements. Watching that line during a
 * demo answers "what happens if I change this?" without anything being spent.
 *
 * <p>The count is a {@code SELECT COUNT} rather than
 * {@code job.getApplications().size()} on purpose — the association is LAZY and
 * this can be called from outside a transaction, so touching it would either
 * lean on open-in-view or throw.
 */
@Component
public class NoOpApplicationReevaluationTrigger implements ApplicationReevaluationTrigger {

    private static final Logger log = LoggerFactory.getLogger(NoOpApplicationReevaluationTrigger.class);

    private final ApplicationRepository applications;

    public NoOpApplicationReevaluationTrigger(ApplicationRepository applications) {
        this.applications = applications;
    }

    @Override
    public void onRequirementsChanged(Job job) {
        log.info("Requirements changed for job {} (now version {}): {} application(s) would be re-evaluated. "
                        + "No-op trigger is active, so nothing was queued.",
                job.getId(), job.getRequirementsVersion(), applications.countByJob(job));
    }
}
