package com.apliman.cvevaluator.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The thread pool evaluation runs on.
 *
 * <h2>Why a dedicated pool and not the default one</h2>
 *
 * Extraction already uses Spring's auto-configured executor. Sharing it would
 * let a burst of re-evaluations — each holding a thread for twenty seconds of
 * network wait — starve the extraction of newly uploaded CVs, so an upload
 * would sit at PENDING because a recruiter edited a job. The two workloads have
 * nothing in common except that neither belongs on a request thread, and they
 * fail differently, so they get separate pools.
 */
@Configuration(proxyBeanMethods = false)
public class EvaluationExecutorConfig {

    public static final String EVALUATION_EXECUTOR = "evaluationExecutor";

    private static final Logger log = LoggerFactory.getLogger(EvaluationExecutorConfig.class);

    /**
     * Two threads, queue of 200, caller-runs when both are exhausted.
     *
     * <p><strong>Two, not more.</strong> These threads spend essentially all
     * their time blocked on the model, so the CPU argument for sizing does not
     * apply — the binding constraint is the provider's rate limit, and a wide
     * pool would convert a queue into a burst of 429s. Two is enough to keep
     * the pipe full at roughly twenty seconds per call while staying far below
     * any per-minute cap.
     *
     * <p><strong>The queue is deep on purpose.</strong> One requirements edit
     * on a job with twenty applications enqueues twenty tasks at once; a queue
     * shorter than that would reject work that is perfectly fine to do slowly.
     *
     * <p><strong>CallerRunsPolicy is the part to understand.</strong> When the
     * queue is also full, the submitting thread runs the task itself. For the
     * requirements endpoint that means the request blocks — the old behaviour,
     * and unpleasant — but the alternative is
     * {@code AbortPolicy} silently dropping an evaluation a recruiter is
     * waiting for. Back-pressure that is felt beats work that vanishes. It is
     * reachable only past 200 queued evaluations, which on this corpus does not
     * happen.
     */
    @Bean(name = EVALUATION_EXECUTOR)
    public TaskExecutor evaluationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("cv-evaluate-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Let in-flight evaluations finish on shutdown rather than killing them
        // mid-call. A model call that has already been paid for should be
        // allowed to produce its row. The timeout bounds how long that grace
        // lasts, so a hung provider cannot stop the JVM exiting.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();
        log.info("Evaluation executor: {} threads, queue {}", executor.getCorePoolSize(), 200);
        return executor;
    }
}
