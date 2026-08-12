package com.apliman.cvevaluator.application;

/**
 * No application with that id. Answered as a 404 by
 * {@code GlobalExceptionHandler}.
 *
 * <p>Its own type rather than a reused {@code IllegalArgumentException}, for
 * the same reason {@code JobNotFoundException} is: that handler answers 400,
 * and "you asked for something that does not exist" is not a malformed request.
 */
public class ApplicationNotFoundException extends RuntimeException {

    public ApplicationNotFoundException(Long id) {
        super("Application " + id + " not found");
    }
}
