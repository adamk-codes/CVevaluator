package com.apliman.cvevaluator.storage;

import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * Where CV files live. The seam exists so the filesystem implementation can be
 * swapped for object storage later without touching callers.
 *
 * <p>Implementations must not be {@code @Transactional}: per project rule the
 * file is written to disk <em>before</em> the {@code Application} row is
 * inserted, so {@link #store} is called outside any transaction.
 */
public interface StorageService {

    /**
     * Stores the upload under a generated filename and returns a handle to it.
     *
     * @throws InvalidUploadException if the file is empty, unnamed, or its
     *                               extension is not on the allowlist
     * @throws StorageException      if the bytes could not be written
     */
    StoredFile store(MultipartFile file);

    /**
     * Resolves a {@code storageKey} previously returned by {@link #store} —
     * typically read back out of the database — to a readable path.
     *
     * @throws InvalidUploadException if the key resolves outside the storage root
     * @throws StorageException      if the file is missing or unreadable
     */
    Path load(String storageKey);
}
