package com.apliman.cvevaluator.storage;

/**
 * Storage failed for a reason that is not the caller's fault — I/O error,
 * unreadable root, a stored file that has gone missing.
 *
 * <p>Maps to 500 with a fixed client-facing message; the real cause is logged.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
