package com.apliman.cvevaluator.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Evaluation configuration, bound from {@code cvevaluator.evaluation.*}.
 */
@ConfigurationProperties("cvevaluator.evaluation")
public record EvaluationProperties(
        Integer maxPerApplication,
        Duration extractionWait,
        Duration extractionPollInterval
) {

    /**
     * How many evaluations one application keeps before the oldest are dropped.
     *
     * <p>Five, for a reason that is about what the history is <em>for</em>. Its
     * only job is to let someone see that a requirements edit is what moved a
     * candidate — which needs the current evaluation and enough of the previous
     * ones to show a trend. Five covers four consecutive requirement edits, well
     * past what any single job goes through before a decision is made.
     *
     * <p>The cost of a larger number is not row count, it is row size: every
     * evaluation carries two {@code jsonb} blobs and a summary, so an
     * unbounded history on a job whose requirements are edited repeatedly grows
     * by a few kilobytes per application per edit, forever, for rows nobody
     * reads. Three would also be defensible; five was chosen because losing the
     * oldest of three is noticeable when comparing across two edits.
     */
    private static final int DEFAULT_MAX_PER_APPLICATION = 5;

    /**
     * How long a re-evaluation waits for a CV that is still being extracted.
     *
     * <p>Sixty seconds, sized against what it is waiting for rather than picked
     * round: extraction is a PDFBox or POI parse of a file under 10MB, which
     * finishes in well under a second for every fixture in the corpus. The
     * minute is almost entirely queue time — {@code spring.task.execution} runs
     * 2 core threads with a 50-deep queue, so a burst of uploads can leave a CV
     * waiting behind others before its own parse even starts.
     *
     * <p>It is a timeout and not a guarantee. Past it the application is skipped
     * rather than evaluated on absent text, and it picks up an evaluation on the
     * next requirements change.
     */
    private static final Duration DEFAULT_EXTRACTION_WAIT = Duration.ofSeconds(60);

    /**
     * How often to re-read an application while waiting for it.
     *
     * <p>Polling, rather than waiting on the extraction thread directly, because
     * the two never share a thread or a transaction — extraction runs on the
     * {@code cv-extract-} pool and commits its own short transactions, so the
     * only way to observe it finishing is to read the row again. Half a second
     * is short enough that a fast extraction is not padded out by the poll and
     * long enough that a full wait is 120 queries, not thousands.
     */
    private static final Duration DEFAULT_EXTRACTION_POLL_INTERVAL = Duration.ofMillis(500);

    public EvaluationProperties {
        // Below 1 there is no current evaluation to read, which breaks every
        // consumer rather than merely trimming history - so unlike the
        // extraction threshold, 0 is not an honoured setting here.
        if (maxPerApplication == null || maxPerApplication < 1) {
            maxPerApplication = DEFAULT_MAX_PER_APPLICATION;
        }
        // Zero IS honoured here, and means "never wait": evaluate whatever has
        // text right now and skip the rest. That is the old behaviour, and it is
        // a reasonable thing to want back when a demo must not block.
        if (extractionWait == null || extractionWait.isNegative()) {
            extractionWait = DEFAULT_EXTRACTION_WAIT;
        }
        // A zero or negative poll interval would spin the CPU and hammer the
        // database, so it is corrected rather than honoured.
        if (extractionPollInterval == null
                || extractionPollInterval.isNegative()
                || extractionPollInterval.isZero()) {
            extractionPollInterval = DEFAULT_EXTRACTION_POLL_INTERVAL;
        }
    }
}
