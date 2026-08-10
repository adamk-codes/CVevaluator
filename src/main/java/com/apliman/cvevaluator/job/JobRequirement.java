package com.apliman.cvevaluator.job;

/**
 * One authored requirement on a {@link Job}.
 *
 * <p>Written by the recruiter, never inferred by the LLM. That is the whole
 * point: the model's job is to decide whether a CV satisfies a requirement, not
 * to decide what the requirements are. Both at once and there is nothing left to
 * check its answer against.
 *
 * <p>A value object, not an entity. Requirements have no lifecycle of their own
 * — they are never addressed individually, never referenced from another table,
 * and are replaced as a whole list on edit — so they live in a {@code jsonb}
 * column on {@code jobs} rather than in a table of their own. The alternative,
 * a {@code job_requirements} entity with a generated id, buys per-row addressing
 * that nothing in this design uses and costs a join on every read.
 *
 * @param id   stable within one job, e.g. {@code "R1"}. An assessment cites this,
 *             so it must survive being written down. It is <em>not</em> stable
 *             across a requirements edit: a replaced list may reuse {@code R1}
 *             for different text, which is why an evaluation records the
 *             {@code requirementsVersion} it was made against.
 * @param text the requirement itself, atomic and checkable against a CV
 * @param kind see {@link RequirementKind}
 */
public record JobRequirement(String id, String text, RequirementKind kind) {
}
