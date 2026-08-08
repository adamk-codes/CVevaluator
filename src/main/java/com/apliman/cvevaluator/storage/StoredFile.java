package com.apliman.cvevaluator.storage;

/**
 * The result of storing an upload.
 *
 * <p>{@code storageKey} is a bare generated filename relative to the storage
 * root, and is what goes into {@code Application.storagePath}. It is relative
 * on purpose: the storage root can move between machines without rewriting
 * rows, and a row can never name a path outside the root.
 *
 * <p>{@code originalFilename} is the candidate's own name for the file. It is a
 * display value only and is never used to build a path.
 */
public record StoredFile(
        String storageKey,
        String originalFilename,
        String extension,
        long sizeBytes
) {
}
