package com.apliman.cvevaluator.application;

/**
 * Published once a CV has usable text, so it can be evaluated.
 *
 * <p>The seam that makes a submitted CV produce a verdict on its own. Before
 * it, an application reached COMPLETED and stopped there — an evaluation only
 * appeared when a recruiter next edited the job's requirements, which for the
 * last CV submitted might be never.
 *
 * <p>Carries only the id. Unlike {@link ApplicationCreatedEvent}, which passes
 * the storage key so the listener needs no lookup of its own, the evaluation
 * listener has to load the application anyway — it needs the redacted text and
 * the job, neither of which belongs in an event payload.
 *
 * <p>Subscribers must bind to {@code AFTER_COMMIT}. This is published while the
 * transaction that set the text is still open, so a listener running any
 * earlier would read a row whose {@code redactedText} its own connection cannot
 * see yet, and evaluate an empty CV.
 */
public record CvExtractedEvent(Long applicationId) {
}
