package com.bloxbean.julc.cli.cmd.verify;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerificationProgressTest {

    @Test
    void rendersLineOrientedStatusAndElapsedTime() {
        var bytes = new ByteArrayOutputStream();
        var clock = new AtomicLong();
        var progress = VerificationProgress.testing(
                new PrintStream(bytes, true, StandardCharsets.UTF_8), clock::get);

        progress.heading("Running verification ...");
        try (var task = progress.start("Selecting verification backend")) {
            clock.set(1_250_000_000L);
            task.succeed("local");
        }

        assertEquals("""
                Running verification ...
                  Selecting verification backend ... OK [1.3 s] - local
                """, bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unfinishedTaskFailsInsteadOfLeavingAnOpenProgressLine() {
        var bytes = new ByteArrayOutputStream();
        var clock = new AtomicLong();
        var progress = VerificationProgress.testing(
                new PrintStream(bytes, true, StandardCharsets.UTF_8), clock::get);

        try (var ignored = progress.start("Preparing Docker backend\nplease wait")) {
            clock.set(65_000_000_000L);
        }

        assertEquals("  Preparing Docker backend please wait ... FAILED [1m 5s]\n",
                bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rendersSkippedStepAsACompleteLine() {
        var bytes = new ByteArrayOutputStream();
        var progress = VerificationProgress.testing(
                new PrintStream(bytes, true, StandardCharsets.UTF_8), () -> 0L);

        progress.skipped("Proving required signer", "property is vacuous\nnot attempted");

        assertEquals("  Proving required signer ... SKIPPED"
                        + " - property is vacuous not attempted\n",
                bytes.toString(StandardCharsets.UTF_8));
    }
}
