package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.core.ProgressListener;

import java.io.IOException;
import java.nio.file.Path;

/** Runs the shared restore skeleton for any source. */
public final class RestoreOperation {
    public record Options(boolean dryRun, boolean deleteExtra, int threads, int compressionLevel) {
    }

    public record Outcome(Path target, RestoreStats stats, boolean deleted) {
    }

    /** A checked plan whose target lock is held until the session closes. */
    public static final class Session implements AutoCloseable {
        private final WorldTarget target;
        private final RestoreSource source;
        private final RestorePlan plan;

        private Session(WorldTarget target, RestoreSource source, RestorePlan plan) {
            this.target = target;
            this.source = source;
            this.plan = plan;
        }

        public RestoreStats stats() {
            return plan.stats();
        }

        public Path target() {
            return target.directory();
        }

        public Outcome execute(boolean deleteExtra, int threads, int compressionLevel, ProgressListener listener)
                throws IOException {
            RestoreWriter.write(target, source, plan, threads, compressionLevel, listener);
            if (deleteExtra) RestoreDeleter.delete(target, plan, listener);
            return new Outcome(target.directory(), plan.stats(), deleteExtra);
        }

        @Override
        public void close() throws IOException {
            target.close();
        }
    }

    public static Session prepare(RestoreSource source, Path output, int threads, ProgressListener listener)
            throws IOException {
        WorldTarget target = WorldTarget.open(output);
        try {
            RestorePlan plan = RestoreChecker.check(target, source, threads, listener);
            return new Session(target, source, plan);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                target.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static Outcome run(RestoreSource source, Path output, Options options, ProgressListener listener)
            throws IOException {
        try (Session session = prepare(source, output, options.threads(), listener)) {
            if (options.dryRun()) return new Outcome(session.target(), session.stats(), false);
            return session.execute(options.deleteExtra(), options.threads(), options.compressionLevel(), listener);
        }
    }
}
