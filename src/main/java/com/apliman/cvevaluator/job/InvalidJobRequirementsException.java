package com.apliman.cvevaluator.job;

/**
 * A submitted requirements list broke one of the rules in
 * {@link JobRequirementsValidator}.
 *
 * <p>Every message is authored in that class and names only what the client
 * sent, so {@code GlobalExceptionHandler} can return it verbatim. Nothing here
 * carries a server path, a SQL fragment or a Hibernate internal.
 */
public class InvalidJobRequirementsException extends RuntimeException {

    public InvalidJobRequirementsException(String message) {
        super(message);
    }
}
