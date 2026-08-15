package com.bloxbean.julc.cli.cmd.verify;

import java.io.PrintStream;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Small, line-oriented progress reporter for long verification operations. */
final class VerificationProgress {

    private static final VerificationProgress SILENT =
            new VerificationProgress(null, System::nanoTime);

    private final PrintStream out;
    private final LongSupplier nanoTime;

    private VerificationProgress(PrintStream out, LongSupplier nanoTime) {
        this.out = out;
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    static VerificationProgress silent() {
        return SILENT;
    }

    static VerificationProgress console(PrintStream out) {
        return new VerificationProgress(Objects.requireNonNull(out), System::nanoTime);
    }

    static VerificationProgress testing(PrintStream out, LongSupplier nanoTime) {
        return new VerificationProgress(Objects.requireNonNull(out), nanoTime);
    }

    void heading(String message) {
        if (out != null) out.println(message);
    }

    void skipped(String message, String detail) {
        if (out == null) return;
        String suffix = detail == null || detail.isBlank()
                ? "" : " - " + oneLine(detail);
        out.println("  " + oneLine(message) + " ... SKIPPED" + suffix);
    }

    Task start(String message) {
        if (out != null) {
            out.print("  " + oneLine(message) + " ... ");
            out.flush();
        }
        return new Task(nanoTime.getAsLong());
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) return "Verification step";
        return value.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private static String elapsed(long nanos) {
        long millis = Math.max(0L, nanos / 1_000_000L);
        if (millis < 1_000L) return millis + " ms";
        if (millis < 60_000L) {
            return String.format(Locale.ROOT, "%.1f s", millis / 1_000.0);
        }
        long seconds = millis / 1_000L;
        return (seconds / 60L) + "m " + (seconds % 60L) + "s";
    }

    final class Task implements AutoCloseable {
        private final long startedAt;
        private boolean finished;

        private Task(long startedAt) {
            this.startedAt = startedAt;
        }

        void succeed() {
            succeed(null);
        }

        void succeed(String detail) {
            finish("OK", detail);
        }

        void complete(String detail) {
            finish("DONE", detail);
        }

        void fail() {
            fail(null);
        }

        void fail(String detail) {
            finish("FAILED", detail);
        }

        private void finish(String status, String detail) {
            if (finished) return;
            finished = true;
            if (out == null) return;
            String suffix = detail == null || detail.isBlank()
                    ? "" : " - " + oneLine(detail);
            out.println(status + " [" + elapsed(nanoTime.getAsLong() - startedAt) + "]" + suffix);
        }

        @Override
        public void close() {
            if (!finished) fail();
        }
    }
}
