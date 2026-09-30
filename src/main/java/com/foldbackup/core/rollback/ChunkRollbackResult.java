package com.foldbackup.core.rollback;

/**
 * Result of one online or offline chunk rollback operation.
 *
 * @param success whether the operation succeeded
 * @param message human-readable detail (success description or error)
 */
public record ChunkRollbackResult(boolean success, String message) {
}
