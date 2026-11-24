package io.github.truscdo.foldbackup.core.rollback;

import java.io.IOException;
import java.util.Locale;

/**
 * Scope of a single-chunk rollback operation.
 */
public enum RollbackScope {
    /** Restore terrain, block entities, recoverable entities, and POI. */
    ALL,
    /** Restore terrain, block entities, and POI while preserving current entities. */
    BLOCKS,
    /** Restore recoverable entities while leaving terrain, block entities, and POI untouched. */
    ENTITIES;

    public boolean includesBlocks() {
        return this != ENTITIES;
    }

    public boolean includesEntities() {
        return this != BLOCKS;
    }

    public static RollbackScope parse(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IOException("Invalid rollback scope: " + raw + " (expected all, blocks, or entities)");
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "all" -> ALL;
            case "blocks" -> BLOCKS;
            case "entities" -> ENTITIES;
            default -> throw new IOException(
                    "Invalid rollback scope: " + raw + " (expected all, blocks, or entities)");
        };
    }
}
