package com.apliman.cvevaluator.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Evaluation configuration, bound from {@code cvevaluator.evaluation.*}.
 */
@ConfigurationProperties("cvevaluator.evaluation")
public record EvaluationProperties(Integer maxPerApplication) {

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

    public EvaluationProperties {
        // Below 1 there is no current evaluation to read, which breaks every
        // consumer rather than merely trimming history - so unlike the
        // extraction threshold, 0 is not an honoured setting here.
        if (maxPerApplication == null || maxPerApplication < 1) {
            maxPerApplication = DEFAULT_MAX_PER_APPLICATION;
        }
    }
}
