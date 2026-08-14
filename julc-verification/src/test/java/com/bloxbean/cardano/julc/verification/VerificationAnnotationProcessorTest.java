package com.bloxbean.cardano.julc.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationAnnotationProcessorTest {
    @TempDir
    Path tempDir;

    @Test
    void javacReportsInvalidPathOnAnnotation() throws Exception {
        Path source = tempDir.resolve("Bad.java");
        Files.writeString(source, """
                import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;
                @interface SpendingValidator {}
                @RequiresSigner("redeemer.owner")
                @SpendingValidator
                class Bad {}
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            Files.createDirectories(tempDir.resolve("classes"));
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,
                    List.of(tempDir.resolve("classes")));
            var units = files.getJavaFileObjects(source);
            boolean success = compiler.getTask(null, files, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path"),
                            "-processor", "com.bloxbean.cardano.julc.verification.processor."
                                    + "VerificationAnnotationProcessor"),
                    null, units).call();
            assertFalse(success);
        }
        assertTrue(diagnostics.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getMessage(null)
                        .contains("datum.<field>")));
    }

    @Test
    void javacReportsIncompleteStatefulProfile() throws Exception {
        Path source = tempDir.resolve("Partial.java");
        Files.writeString(source, """
                import com.bloxbean.cardano.julc.verification.annotation.*;
                @interface SpendingValidator {}
                @RequiresSigner("datum.owner")
                @Monotonic(current="datum.state", next="redeemer.nextState",
                    relation=Relation.GREATER_THAN)
                @SpendingValidator
                class Partial {}
                """);
        var diagnostics = compile(source);
        assertTrue(diagnostics.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getMessage(null)
                        .contains("requires @RequiresSigner, @Monotonic, and @PreservesValue")));
    }

    @Test
    void javacReportsMalformedControlledMintLiterals() throws Exception {
        Path source = tempDir.resolve("BadMint.java");
        Files.writeString(source, """
                import com.bloxbean.cardano.julc.verification.annotation.*;
                @interface MintingValidator {}
                @ControlledMint(authority="00", tokenName="abc", quantity=0,
                    action=MintAction.MINT)
                @MintingValidator
                class BadMint {}
                """);
        var diagnostics = compile(source);
        assertTrue(diagnostics.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getMessage(null).contains("28 hexadecimal")));
        assertTrue(diagnostics.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getMessage(null).contains("0 to 32")));
        assertTrue(diagnostics.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getMessage(null).contains("strictly positive")));
    }

    private DiagnosticCollector<JavaFileObject> compile(Path source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            Path output = tempDir.resolve(source.getFileName() + "-classes");
            Files.createDirectories(output);
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            compiler.getTask(null, files, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path"),
                            "-processor", "com.bloxbean.cardano.julc.verification.processor."
                                    + "VerificationAnnotationProcessor"),
                    null, files.getJavaFileObjects(source)).call();
        }
        return diagnostics;
    }
}
