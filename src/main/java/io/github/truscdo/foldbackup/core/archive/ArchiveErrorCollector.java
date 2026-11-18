package io.github.truscdo.foldbackup.core.archive;

import java.util.ArrayList;
import java.util.List;

/** Collects per-archive {@code "fileName: reason"} errors while guarding against duplicate prefixes. */
final class ArchiveErrorCollector {
    private final List<String> errors;

    ArchiveErrorCollector() {
        this(new ArrayList<>());
    }

    private ArchiveErrorCollector(List<String> backing) {
        this.errors = backing;
    }

    static ArchiveErrorCollector wrap(List<String> backing) {
        return new ArchiveErrorCollector(backing);
    }

    void add(String fileName, String reason) {
        errors.add(fileName + ": " + reason);
    }

    void addIfUnprefixed(String fileName, String message) {
        errors.add(message.startsWith(fileName + ":") ? message : fileName + ": " + message);
    }

    void addRaw(String message) {
        errors.add(message);
    }

    boolean isEmpty() {
        return errors.isEmpty();
    }

    int size() {
        return errors.size();
    }

    List<String> list() {
        return errors;
    }
}
