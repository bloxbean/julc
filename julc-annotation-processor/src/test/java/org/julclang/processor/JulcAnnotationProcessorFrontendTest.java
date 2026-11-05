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

/** ADR-068 M6 removed {@code -Ajulc.frontend}: naming another frontend stops the processor with what to do. */
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

    /** ADR-068 M5: a rejection of javac's frontend shows its code and fix, here {@code byte[].equals}'s remedy. */
    @Test
    void aRejectionShowsItsCodeAndFix(@TempDir Path out) throws Exception {
        String source = validator("byte[] a = new byte[]{1}; return a.equals(new byte[]{1});");
        var errors = errors(process(out, source));
        assertTrue(errors.stream().anyMatch(message -> message.contains("JULC0087")
                && message.contains("Builtins.equalsByteString(a, b)")), errors::toString);
    }

    @Test
    void naming_javaparser_stopsWithWhatToDo(@TempDir Path out) throws Exception {
        var errors = errors(process(out, validator("return true;"), "-Ajulc.frontend=javaparser"));
        assertTrue(errors.stream().anyMatch(message -> message.contains("The Java frontend option is gone")), errors::toString);
    }

    @Test
    void naming_javac_compiles(@TempDir Path out) throws Exception {
        assertTrue(errors(process(out, validator("return true;"), "-Ajulc.frontend=javac")).isEmpty());
    }
}
