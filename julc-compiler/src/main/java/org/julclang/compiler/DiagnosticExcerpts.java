package org.julclang.compiler;

import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.SourceExcerpt;
import org.julclang.compiler.frontend.javac.JavacFrontend;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Gives each diagnostic of a compilation the source line it points at. A source is found by the name javac's source file
 * carries, which is what the diagnostic names. When two sources share that name no excerpt is shown, because a wrong line
 * would mislead more than none.
 */
final class DiagnosticExcerpts {

    /** A diagnostic that names no file. */
    private static final String UNNAMED = "<source>";

    private DiagnosticExcerpts() {}

    static List<CompilerDiagnostic> attach(List<CompilerDiagnostic> diagnostics, JavacFrontend.Unit root, List<JavacFrontend.Unit> pool,
                                           Function<JavacFrontend.Unit, String> nameOf) {
        var units = new ArrayList<JavacFrontend.Unit>();
        units.add(root);
        units.addAll(pool);
        var attached = new ArrayList<CompilerDiagnostic>(diagnostics.size());
        for (var diagnostic : diagnostics) {
            // A diagnostic about a file as a whole holds the placeholder position 1:1, which shows no line.
            boolean positioned = diagnostic.line() > 0 && !(diagnostic.line() == 1 && diagnostic.column() == 1);
            String source = diagnostic.excerpt() != null || !positioned ? null : sourceOf(diagnostic.fileName(), units, nameOf);
            attached.add(source == null ? diagnostic
                    : diagnostic.withExcerpt(SourceExcerpt.of(source, diagnostic.line(), diagnostic.column())));
        }
        return attached;
    }

    private static String sourceOf(String file, List<JavacFrontend.Unit> units, Function<JavacFrontend.Unit, String> nameOf) {
        if (file == null || file.equals(UNNAMED)) return units.size() == 1 ? units.getFirst().text() : null;
        String found = null;
        for (var unit : units) {
            if (!names(unit, nameOf).contains(file)) continue;
            if (found != null) return null;
            found = unit.text();
        }
        return found;
    }

    /** The full path and the short name of a file, as javac's errors and JuLC's name it, or a string source's name. */
    private static List<String> names(JavacFrontend.Unit unit, Function<JavacFrontend.Unit, String> nameOf) {
        if (unit.path() != null) return List.of(unit.path().toString(), unit.path().getFileName().toString());
        return List.of(nameOf.apply(unit));
    }
}
