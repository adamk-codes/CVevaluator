package com.apliman.cvevaluator.job;

/**
 * Told that a job's requirements changed, so the assessments made against the
 * old ones are now stale.
 *
 * <p>A seam, in the same spirit as {@code CurrentUserProvider}: one method, one
 * implementation, and a one-bean swap. It was introduced while the only
 * implementation logged and returned, so that the call site in
 * {@code JobController} was written and tested before the thing it calls
 * existed. {@link AsyncApplicationReevaluationTrigger} is that implementation
 * now; the interface stays because the call site is still tested against it
 * with a mock, and because it is what keeps {@code JobController} from
 * depending on the evaluation package.
 *
 * <h2>What an implementation must do</h2>
 *
 * Recorded here because it is a correctness contract, not an implementation
 * detail — it binds any future replacement, not just the current one:
 *
 * <ul>
 *   <li><strong>Insert new {@code Evaluation} rows only.</strong> Never update
 *       a prior evaluation, and never delete one directly. An assessment is a
 *       record of what was said about a candidate at a point in time;
 *       overwriting one destroys the evidence that the requirements change is
 *       what moved the score, which is the only reason to keep the history at
 *       all. Trimming the history to its retention cap is
 *       {@code EvaluationService}'s job, in the same transaction as the insert
 *       — an implementation that deletes rows on its own is doing something
 *       this contract does not permit.
 *   <li><strong>Stamp each new row with the job's current
 *       {@code requirementsVersion}.</strong> Without it two evaluations of the
 *       same application are indistinguishable and neither can be tied back to
 *       the requirement text it actually answered — requirement ids are reused
 *       across versions, so {@code R1} alone does not identify anything.
 *   <li><strong>Skip applications whose CV extraction is {@code FAILED}.</strong>
 *       There is no text to evaluate. Re-running them produces an assessment
 *       grounded in nothing, which is precisely the failure the grounding
 *       checker exists to catch.
 * </ul>
 */
public interface ApplicationReevaluationTrigger {

    /**
     * @param job the job as it now stands, with its incremented
     *            {@code requirementsVersion} already applied
     */
    void onRequirementsChanged(Job job);
}
