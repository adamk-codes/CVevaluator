package com.apliman.cvevaluator.job;

/**
 * Told that a job's requirements changed, so the assessments made against the
 * old ones are now stale.
 *
 * <p>A seam, in the same spirit as {@code CurrentUserProvider}: one method, one
 * implementation today ({@link NoOpApplicationReevaluationTrigger}), and a
 * one-bean swap once there is an evaluator to call. The interface exists now so
 * that the call site in {@code JobController} is written and tested before the
 * thing it calls exists, rather than the endpoint being reopened later.
 *
 * <h2>What the real implementation must do</h2>
 *
 * Recorded here because it is a correctness contract, not an implementation
 * detail, and the bean that has to honour it has not been written yet:
 *
 * <ul>
 *   <li><strong>Insert new {@code Evaluation} rows only.</strong> Never update,
 *       never delete a prior evaluation. An assessment is a record of what was
 *       said about a candidate at a point in time; overwriting one destroys the
 *       evidence that the requirements change is what moved the score, which is
 *       the only reason to keep the history at all.
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
