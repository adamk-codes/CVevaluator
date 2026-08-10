package com.apliman.cvevaluator.job;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apliman.cvevaluator.application.ApplicationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The no-op trigger logs and does nothing else.
 *
 * <p>"Does nothing else" is the assertion that matters and the reason this is a
 * test rather than a shrug: while this bean is the only implementation, the PUT
 * endpoint's behaviour is defined by it, and a placeholder that quietly did
 * something would be worse than one that is loudly inert.
 */
class NoOpApplicationReevaluationTriggerTest {

    private ApplicationRepository applications;
    private NoOpApplicationReevaluationTrigger trigger;
    private ListAppender<ILoggingEvent> logged;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        applications = mock(ApplicationRepository.class);
        trigger = new NoOpApplicationReevaluationTrigger(applications);

        logged = new ListAppender<>();
        logged.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        logged.start();
        logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(NoOpApplicationReevaluationTrigger.class);
        logger.addAppender(logged);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logged);
    }

    @Test
    void onRequirementsChanged_logsTheJobIdAndApplicationCountAtInfo() {
        when(applications.countByJob(any(Job.class))).thenReturn(3L);

        trigger.onRequirementsChanged(job());

        assertThat(logged.list).hasSize(1);
        ILoggingEvent event = logged.list.getFirst();

        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .contains("job 7")
                .contains("version 2")
                .contains("3 application(s)");
    }

    /**
     * Nothing but the count. If a future edit makes this bean write, delete or
     * queue anything, this fails - which is the whole reason to pin a class that
     * does nothing.
     */
    @Test
    void onRequirementsChanged_touchesNothingBeyondCountingTheApplications() {
        when(applications.countByJob(any(Job.class))).thenReturn(0L);

        Job job = job();
        trigger.onRequirementsChanged(job);

        verify(applications).countByJob(job);
        verifyNoMoreInteractions(applications);
        assertThat(job.getRequirementsVersion()).isEqualTo(2);
    }

    /** A job as it looks after an edit: id assigned, version already bumped. */
    private static Job job() {
        Job job = new Job("Senior Backend Engineer", "desc", "prose", "Senior",
                List.of(new JobRequirement("R1", "5+ years of Java", RequirementKind.MUST_HAVE)),
                null);
        ReflectionTestUtils.setField(job, "id", 7L);
        job.replaceRequirements(
                List.of(new JobRequirement("R1", "3+ years of Python", RequirementKind.MUST_HAVE)));
        return job;
    }
}
