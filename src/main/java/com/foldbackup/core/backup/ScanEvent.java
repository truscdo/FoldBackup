package com.foldbackup.core.backup;

/**
 * Type-safe scan progress events emitted by {@link RegionProcessor}
 * during world scanning.
 * <p>
 * Replaces the earlier string-based callback protocol
 * ({@code "region|relPath|processing"}) with a sealed type hierarchy
 * that can be extended in the future without breaking pattern-matching
 * consumers.
 */
public sealed interface ScanEvent {

    /** Relative path of the file being processed. */
    String relPath();

    /** A region (.mca) file is being scanned. */
    record Region(String relPath) implements ScanEvent {}

    /** A non-region file is being scanned. */
    record File(String relPath) implements ScanEvent {}
    record Completed(String relPath, boolean region, boolean failed) implements ScanEvent {}
}
