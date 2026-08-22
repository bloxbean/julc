package com.bloxbean.cardano.julc.verification.dsl.worker;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DslWorkerRunnerTest {
    @Test
    void resolvesJavaFromHomeAndPathWithoutAssumingNativeImageJavaHome() throws Exception {
        Path expected = Path.of(System.getProperty("java.home"), "bin", javaName())
                .toAbsolutePath().normalize();

        assertEquals(expected, DslWorkerRunner.resolveJavaExecutable(
                System.getProperty("java.home"), null, null));
        assertEquals(expected, DslWorkerRunner.resolveJavaExecutable(
                null, null, expected.getParent().toString()));
    }

    @Test
    void missingWorkerJavaHasActionableDiagnostic() {
        var failure = assertThrows(java.io.IOException.class,
                () -> DslWorkerRunner.resolveJavaExecutable(null, null, null));

        org.junit.jupiter.api.Assertions.assertTrue(
                failure.getMessage().contains("JAVA_HOME or PATH"));
    }

    private static String javaName() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
    }
}
