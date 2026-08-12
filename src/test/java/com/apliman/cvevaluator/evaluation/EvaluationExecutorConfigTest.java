package com.apliman.cvevaluator.evaluation;

import com.apliman.cvevaluator.job.ApplicationReevaluationTrigger;
import com.apliman.cvevaluator.job.AsyncApplicationReevaluationTrigger;
import com.apliman.cvevaluator.job.Job;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evaluation pool, and the annotation that puts work on it.
 */
class EvaluationExecutorConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EvaluationExecutorConfig.class);

    @Test
    void theExecutorIsRegisteredUnderTheNameTheAnnotationUses() {
        contextRunner.run(context -> assertThat(context)
                .hasBean(EvaluationExecutorConfig.EVALUATION_EXECUTOR)
                .getBean(EvaluationExecutorConfig.EVALUATION_EXECUTOR, TaskExecutor.class)
                .isInstanceOf(ThreadPoolTaskExecutor.class));
    }

    /**
     * The pool is narrow because its threads are blocked on a network call, not
     * busy on a CPU: the binding constraint is the provider's rate limit, and a
     * wide pool turns a queue into a burst of 429s.
     */
    @Test
    void thePoolIsNarrowAndTheQueueIsDeep() {
        contextRunner.run(context -> {
            ThreadPoolTaskExecutor executor =
                    context.getBean(EvaluationExecutorConfig.EVALUATION_EXECUTOR, ThreadPoolTaskExecutor.class);

            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("cv-evaluate-");
        });
    }

    /**
     * Rejected work must run on the caller, never be discarded. The default
     * policy for a full queue is to throw, which for this pipeline would mean
     * an evaluation a recruiter is waiting for silently never happening.
     */
    @Test
    void afullQueueAppliesBackPressureRatherThanDroppingWork() {
        contextRunner.run(context -> {
            ThreadPoolTaskExecutor executor =
                    context.getBean(EvaluationExecutorConfig.EVALUATION_EXECUTOR, ThreadPoolTaskExecutor.class);

            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        });
    }

    /**
     * Pins {@code @Async} on the sweep, by reflection, because its absence is
     * invisible.
     *
     * <p>Delete the annotation and nothing fails: the code still runs, every
     * test still passes, the batch simply moves back onto the request thread
     * and {@code PUT /api/jobs/{id}/requirements} starts blocking for minutes
     * again. Nothing else in the suite can catch that — the trigger's own tests
     * construct the class directly, so no proxy exists and the annotation is
     * inert there by design.
     */
    @Test
    void theSweepIsAnnotatedToRunOnTheEvaluationPool() throws NoSuchMethodException {
        Method sweep = AsyncApplicationReevaluationTrigger.class
                .getMethod("onRequirementsChanged", Job.class);

        Async async = sweep.getAnnotation(Async.class);

        assertThat(async).as("@Async is missing; the sweep would run on the request thread").isNotNull();
        assertThat(async.value())
                .as("@Async must name the evaluation pool, or it uses the shared default one")
                .isEqualTo(EvaluationExecutorConfig.EVALUATION_EXECUTOR);
    }

    /**
     * The annotation has to sit on the implementation, not only on the
     * interface. Spring reads it from the target class when proxying, and an
     * interface-only annotation is a well-known way to get silently synchronous
     * behaviour.
     */
    @Test
    void theInterfaceDoesNotCarryTheAnnotationInsteadOfTheImplementation() throws NoSuchMethodException {
        Method declared = ApplicationReevaluationTrigger.class
                .getMethod("onRequirementsChanged", Job.class);

        assertThat(declared.getAnnotation(Async.class))
                .as("the annotation belongs on the implementation; the interface is not proxied from")
                .isNull();
    }
}
