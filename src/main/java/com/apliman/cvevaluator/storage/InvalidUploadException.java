package com.apliman.cvevaluator.storage;

/**
 * The upload itself is unacceptable — empty, no name, a disallowed extension,
 * or a storage key that resolves outside the storage root.
 *
 * <p>Maps to 400. Every message is authored here and never contains a
 * filesystem path, because the handler returns the message to the client.
 */
public class InvalidUploadException extends RuntimeException {

    public InvalidUploadException(String message) {
        super(message);
    }
}
