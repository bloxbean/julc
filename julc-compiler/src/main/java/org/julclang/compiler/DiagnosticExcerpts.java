package org.julclang.compiler;

import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.SourceExcerpt;
import org.julclang.compiler.frontend.javac.JavacFrontend;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Gives each diagnostic of a compilation the source line it points at, from the sources of that compilation. */
final class DiagnosticExcerpts {

    private DiagnosticExcerpts() {}

    static List<CompilerDiagnostic> attach(List<CompilerDiagnostic> diagnostics, JavacFrontend.Unit root, List<JavacFrontend.Unit> pool) {
        var attached = new ArrayList<CompilerDiagnostic>(diagnostics.size());
        for (var diagnostic : diagnostics) {
            String source = sourceOf(diagnostic.fileName(), root, pool);
            attached.add(diagnostic.excerpt() != null ? diagnostic
                    : diagnostic.withExcerpt(SourceExcerpt.of(source, diagnostic.line(), diagnostic.column())));
        }
        return attached;
    }

    /** The text of the source named {@code file}. A diagnostic that names no file is about the root. */
    private static String sourceOf(String file, JavacFrontend.Unit root, List<JavacFrontend.Unit> pool) {
        if (file == null || file.equals("<source>")) return root.text();
        if (declares(root, file)) return root.text();
        for (var unit : pool) {
            if (declares(unit, file)) return unit.text();
        }
        return null;
    }

    private static boolean declares(JavacFrontend.Unit unit, String file) {
        if (unit.path() != null && unit.path().getFileName().toString().equals(file)) return true;
        if (!file.endsWith(".java")) return false;
        String type = file.substring(0, file.length() - ".java".length());
        return Pattern.compile("\\b(class|record|interface|enum)\\s+" + Pattern.quote(type) + "\\b").matcher(unit.text()).find();
    }
}
