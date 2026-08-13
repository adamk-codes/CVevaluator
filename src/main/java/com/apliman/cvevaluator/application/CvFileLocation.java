package com.apliman.cvevaluator.application;

/**
 * Just enough of an {@link Application} to serve its stored file.
 *
 * <p>Not a wire DTO — {@code storageKey} is a server detail and never leaves
 * the process. It exists so the download endpoint can find the file without
 * loading the entity, which carries {@code extractedText} and
 * {@code redactedText}: two {@code TEXT} columns that are eager because they
 * are basic fields, and that a file download has no use for. Streaming a 3KB
 * PDF should not first pull 3KB of parsed text into memory alongside it.
 *
 * @param storageKey       what {@code StorageService.load} resolves, with the
 *                         path-traversal check that makes resolving a
 *                         database-held key safe
 * @param originalFilename what the candidate called it, used for the download
 *                         name — never for locating the file
 */
public record CvFileLocation(String storageKey, String originalFilename) {
}
