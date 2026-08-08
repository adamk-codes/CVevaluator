package com.apliman.cvevaluator.application;

/**
 * Published inside {@link ApplicationService#create} once the row is written.
 *
 * <p>Carries the {@code storageKey} as well as the id so the listener needs no
 * SELECT of its own before it can start reading the file. Both values are in
 * hand at publish time, and with {@code show-sql=true} the effect is visible:
 * the extraction thread issues exactly the statements that change something.
 *
 * <p>Subscribers must bind to {@code AFTER_COMMIT}. The event is published while
 * the creating transaction is still open, so a listener that runs immediately
 * would look up an id that no other connection can see yet.
 */
public record ApplicationCreatedEvent(Long applicationId, String storageKey) {
}
