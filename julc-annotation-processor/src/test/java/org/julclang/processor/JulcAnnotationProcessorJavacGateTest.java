package org.julclang.processor;

import org.julclang.compiler.frontend.javac.JavacGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 D8, M1: the annotation processor's host adapter. Inside the user's javac, the processor compiles each
 * round's validators with the round's same-project libraries and its class loader's bundled sources; the javac gate
 * runs a second, nested javac over them (ADR-068 risk 4).
 */
class JulcAnnotationProcessorJavacGateTest {

    @TempDir
    Path out;
    private String saved;

    static final String FEES = """
            package com.example;

            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.OnchainLibrary;

            @OnchainLibrary
            public class Fees {
                public static BigInteger minimum() { return BigInteger.TWO; }
            }
            """;

    static String validator(String condition) {
        return """
                package com.example;

                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
                import org.julclang.stdlib.lib.MathLib;

                @SpendingValidator
                public class FeeGate {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return %s;
                    }
                }
                """.formatted(condition);
    }

    @BeforeEach
    void enforce() {
        saved = System.getProperty(JavacGate.MODE_PROPERTY);
        System.setProperty(JavacGate.MODE_PROPERTY, "enforce");
    }

    @AfterEach
    void restore() {
        if (saved == null) System.clearProperty(JavacGate.MODE_PROPERTY);
        else System.setProperty(JavacGate.MODE_PROPERTY, saved);
    }

    List<Diagnostic<? extends JavaFileObject>> process(Map<String, String> sources) throws Exception {
        var javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = javac.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            var files = sources.entrySet().stream().map(e -> (JavaFileObject) new SimpleJavaFileObject(
                    URI.create("string:///com/example/" + e.getKey() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return e.getValue();
                }
            }).toList();
            javac.getTask(null, fileManager, diagnostics, List.of("-proc:only", "-processor",
                    JulcAnnotationProcessor.class.getName()), null, files).call();
        }
        return diagnostics.getDiagnostics();
    }

    @Test
    void javaValidatorWithARoundLibraryAndTheStdlibCompiles() throws Exception {
        var diagnostics = process(Map.of("Fees", FEES,
                "FeeGate", validator("MathLib.max(ctx.txInfo().fee(), Fees.minimum()).signum() > 0")));
        assertTrue(diagnostics.stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR), diagnostics.toString());
        assertTrue(Files.exists(out.resolve("META-INF/plutus/FeeGate.plutus.json")));
    }

    @Test
    void javacGateRejectionFailsTheValidator() throws Exception {
        // -proc:only does not attribute, so the user's javac lets the dialect through to the processor; the gate's
        // javac rejects it.
        var diagnostics = process(Map.of("Fees", FEES, "FeeGate", validator("ctx.txInfo().fee() > Fees.minimum()")));
        var errors = diagnostics.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null)).toList();
        assertEquals(1, errors.size(), diagnostics.toString());
        assertTrue(errors.getFirst().contains("JULC0085"), errors.getFirst());
        assertTrue(errors.getFirst().contains("compiler.err.operator.cant.be.applied.1"), errors.getFirst());
    }
}
