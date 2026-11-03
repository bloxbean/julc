package org.julclang.processor;

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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D4: {@code -Ajulc.frontend}, which the Gradle plugin passes, so a project's validators under {@code src/main/java}
 * are compiled by the frontend it chose.
 */
class JulcAnnotationProcessorFrontendTest {

    private static String validator(String body) {
        return """
                package com.example;

                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                public class Fee {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        %s
                    }
                }
                """.formatted(body);
    }

    private static List<Diagnostic<? extends JavaFileObject>> process(Path out, String source, String... options) throws Exception {
        var javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = javac.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///com/example/Fee.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            var arguments = new ArrayList<>(List.of("-proc:only", "-processor", JulcAnnotationProcessor.class.getName(), "-Ajulc.blueprint=false"));
            arguments.addAll(List.of(options));
            javac.getTask(null, fileManager, diagnostics, arguments, null, List.of(file)).call();
        }
        return diagnostics.getDiagnostics();
    }

    private static List<String> errors(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).map(d -> d.getMessage(null)).toList();
    }

    @Test
    void javacCompilesTheScriptTheDefaultDoes(@TempDir Path reference, @TempDir Path javac) throws Exception {
        String source = validator("return ctx.txInfo().signatories().isEmpty();");
        assertEquals(List.of(), errors(process(reference, source)));
        assertEquals(List.of(), errors(process(javac, source, "-Ajulc.frontend=javac")));
        try (var files = Files.walk(reference)) {
            var output = files.filter(Files::isRegularFile).findFirst().orElseThrow();
            assertEquals(Files.readString(output), Files.readString(javac.resolve(reference.relativize(output))));
        }
    }

    /** A construct only {@code javac} stops, {@code Value.fromPlutusData}, shows which frontend compiled. */
    @Test
    void theOptionReachesTheCompilation(@TempDir Path out) throws Exception {
        String source = validator("Value v = Value.fromPlutusData(redeemer); return true;")
                .replace("import org.julclang.ledger.ScriptContext;", "import org.julclang.ledger.ScriptContext;\nimport org.julclang.ledger.Value;");
        assertEquals(List.of(), errors(process(out, source, "-Ajulc.frontend=javaparser")));
        var errors = errors(process(out, source, "-Ajulc.frontend=javac"));
        assertTrue(errors.stream().anyMatch(message -> message.contains("JULC0084")), errors::toString);
    }
}
