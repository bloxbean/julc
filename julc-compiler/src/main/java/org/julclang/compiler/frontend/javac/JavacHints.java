package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.error.DiagnosticCodes;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a javac error into {@code JULC0085} (ADR-068 D5): javac's message, key and position, with the column as a
 * character offset from the line start (I7, not javac's tab-expanded column), and a JuLC hint where a name is
 * missing because JuLC's closed environment does not provide it (I10): a JDK type or package outside the platform
 * blob, an annotation from outside the on-chain API, or a package of neither.
 */
final class JavacHints {

    private static final Pattern MISSING_PACKAGE = Pattern.compile("package ([\\w.]+) does not exist");
    private static final Pattern SYMBOL = Pattern.compile("symbol:\\s+(class|interface|variable|method|package)"
            + "\\s+([\\w.$]+)");
    private static final Pattern LOCATION_PACKAGE = Pattern.compile("location:\\s+package\\s+([\\w.]+)");

    /** JuLC's replacements for the JDK collection types users reach for first: all are in {@code java.util}. */
    private static final Map<String, String> REPLACEMENTS = Map.ofEntries(
            Map.entry("ArrayList", "JulcList"), Map.entry("LinkedList", "JulcList"), Map.entry("Vector", "JulcList"),
            Map.entry("HashMap", "JulcMap"), Map.entry("TreeMap", "JulcMap"), Map.entry("LinkedHashMap", "JulcMap"),
            Map.entry("Hashtable", "JulcMap"));
    private static final String REPLACEMENTS_PACKAGE = "java.util";

    private JavacHints() {
    }

    /**
     * What the parsed unit says around a diagnostic (D5): the source spans of its annotations' type names, and the
     * packages of its JDK on-demand imports. Read from javac's trees, not from the text, so comments and spacing do
     * not matter.
     */
    record UnitContext(List<long[]> annotationSpans, List<String> jdkWildcardImports) {
        static final UnitContext NONE = new UnitContext(List.of(), List.of());

        static UnitContext of(CompilationUnitTree unit, SourcePositions positions) {
            var spans = new ArrayList<long[]>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitAnnotation(AnnotationTree node, Void unused) {
                    spans.add(new long[]{positions.getStartPosition(unit, node.getAnnotationType()),
                            positions.getEndPosition(unit, node.getAnnotationType())});
                    return super.visitAnnotation(node, unused);
                }
            }.scan(unit, null);
            var wildcards = new ArrayList<String>();
            for (ImportTree i : unit.getImports()) {
                if (!i.isStatic() && i.getQualifiedIdentifier() instanceof MemberSelectTree select
                        && select.getIdentifier().contentEquals("*") && isJdk(select.getExpression().toString())) {
                    wildcards.add(select.getExpression().toString());
                }
            }
            return new UnitContext(List.copyOf(spans), List.copyOf(wildcards));
        }

        boolean inAnnotation(long position) {
            return annotationSpans.stream().anyMatch(s -> s[0] <= position && position < s[1]);
        }
    }

    static JavacDiagnostic diagnostic(Diagnostic<? extends JavaFileObject> d, JavacSourceFile source,
                                      UnitContext context) {
        String message = d.getMessage(Locale.ENGLISH);
        String key = d.getCode();
        long position = d.getPosition();
        long line = position == Diagnostic.NOPOS ? 0 : d.getLineNumber();
        long column = 0;
        String text = source == null ? null : source.text();
        if (text != null && position != Diagnostic.NOPOS && position <= text.length()) {
            column = position - lineStart(text, (int) position) + 1;
        }
        String fileName = source != null ? source.displayName()
                : d.getSource() != null ? d.getSource().getName() : null;
        return JavacDiagnostic.of(DiagnosticCodes.JAVAC_ERROR, key, DiagnosticCodes.JAVAC_ERROR.format(message, key),
                fileName, line, column, d.getStartPosition(), d.getEndPosition(),
                hint(key, message, position, context == null ? UnitContext.NONE : context));
    }

    /** The offset of the first character of the line containing {@code position}. */
    static int lineStart(String text, int position) {
        int i = Math.min(position, text.length());
        while (i > 0) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '\r') break;
            i--;
        }
        return i;
    }

    static String hint(String key, String message, long position, UnitContext context) {
        if (key == null) return null;
        if (key.startsWith("compiler.err.doesnt.exist")) {
            Matcher m = MISSING_PACKAGE.matcher(message);
            if (!m.find()) return null;
            String pkg = m.group(1);
            return isJdk(pkg)
                    ? "Package " + pkg + " is not available on-chain: JuLC compiles against a fixed subset of"
                    + " java.base (ADR-068 D3)"
                    : "Package " + pkg + " is not visible to JuLC: only JuLC's on-chain API (julc-core,"
                    + " julc-ledger-api, the stdlib and julc-verification's annotations) and the sources of this"
                    + " compilation are (ADR-068 I10)";
        }
        if (!key.startsWith("compiler.err.cant.resolve")) return null;
        Matcher symbol = SYMBOL.matcher(message);
        if (!symbol.find()) return null;
        String kind = symbol.group(1);
        String name = symbol.group(2);
        if (context.inAnnotation(position)) {
            return "Annotation @" + name + " is not available to JuLC: a validator may use java.lang's annotations"
                    + " and those of JuLC's on-chain API (org.julclang.stdlib.annotation,"
                    + " org.julclang.verification.annotation) (ADR-068 I10)";
        }
        String replacement = REPLACEMENTS.get(name);
        Matcher location = LOCATION_PACKAGE.matcher(message);
        String pkg = location.find() ? location.group(1) : null;
        if (pkg != null && isJdk(pkg)) {
            return pkg + "." + name + " is not available on-chain" + (replacement != null ? "; use " + replacement
                    : ": JuLC compiles against a fixed subset of java.base (ADR-068 D3)");
        }
        if (kind.equals("class") && !context.jdkWildcardImports().isEmpty()) {
            // Only a name known to be a JDK type gets the categorical hint; any other name may as well be a missing
            // project source or import (F513).
            if (replacement != null && context.jdkWildcardImports().contains(REPLACEMENTS_PACKAGE)) {
                return REPLACEMENTS_PACKAGE + "." + name + " is not available on-chain; use " + replacement;
            }
            return "If " + name + " is a JDK type (from "
                    + String.join(", ", context.jdkWildcardImports().stream().map(p -> "import " + p + ".*").toList())
                    + "), it is not available on-chain: JuLC compiles against a fixed subset of java.base (ADR-068"
                    + " D3); otherwise check the import or the source that declares it";
        }
        return null;
    }

    private static boolean isJdk(String pkg) {
        return pkg.equals("java") || pkg.startsWith("java.") || pkg.startsWith("javax.") || pkg.startsWith("jdk.")
                || pkg.startsWith("sun.") || pkg.startsWith("com.sun.");
    }
}
