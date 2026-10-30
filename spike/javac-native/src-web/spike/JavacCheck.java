package spike;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Browser entry for the javac Web Image demo: installs globalThis.javacCheck(source) -> JSON string.
 *
 * javac comes from ToolProvider (public API only) and reads the embedded platform blob (/platform.bin:
 * a minimal java.base plus JuLC's core, ledger-api and stdlib class files on the class path). With program
 * arguments (JVM or Node testing) it checks each argument as a source text and prints the JSON instead.
 */
public final class JavacCheck {

    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "public\\s+(?:(?:final|abstract|sealed|non-sealed|static|strictfp)\\s+)*(?:class|interface|record|enum)\\s+(\\w+)");
    private static final Pattern ANY_TYPE = Pattern.compile("(?:class|interface|record|enum)\\s+(\\w+)");

    private static JavaCompiler compiler;
    private static EmbeddedFileManager fileManager;
    private static double setupMs;

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        compiler = ToolProvider.getSystemJavaCompiler();
        fileManager = EmbeddedFileManager.fromResource("/platform.bin");
        setupMs = (System.nanoTime() - t0) / 1e6;
        if (args.length > 0) {
            for (String source : args) {
                System.out.println(check(source));
            }
            return;
        }
        install(argv -> JSString.of(check(((JSString) argv.get(0)).asString())));
    }

    @JS(args = {"fn"}, value = """
            globalThis.javacCheck = (source) => String(fn([String(source)]));
            if (typeof globalThis.onJavacReady === 'function') {
                setTimeout(() => globalThis.onJavacReady(), 0);
            }
            """)
    private static native void install(Function<JSObject, JSString> fn);

    static String check(String source) {
        long t0 = System.nanoTime();
        StringBuilder json = new StringBuilder("{");
        try {
            String className = typeName(source);
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///" + className + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diags,
                    List.of("-proc:none", "-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"), null, List.of(unit));
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            double analyzeMs = (System.nanoTime() - t0) / 1e6;

            json.append("\"file\":").append(str(className + ".java"));
            json.append(",\"analyzeMs\":").append(String.format(Locale.ROOT, "%.1f", analyzeMs));
            json.append(",\"setupMs\":").append(String.format(Locale.ROOT, "%.1f", setupMs));
            json.append(",\"platformClasses\":").append(fileManager.classCount());
            json.append(",\"diagnostics\":[");
            boolean first = true;
            boolean errors = false;
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                errors |= d.getKind() == Diagnostic.Kind.ERROR;
                if (!first) json.append(',');
                first = false;
                json.append("{\"kind\":").append(str(d.getKind().name()))
                        .append(",\"code\":").append(str(d.getCode()))
                        .append(",\"line\":").append(d.getLineNumber())
                        .append(",\"column\":").append(d.getColumnNumber())
                        .append(",\"start\":").append(d.getStartPosition())
                        .append(",\"end\":").append(d.getEndPosition())
                        .append(",\"position\":").append(d.getPosition())
                        .append(",\"message\":").append(str(d.getMessage(Locale.ROOT)))
                        .append('}');
            }
            json.append(']');
            if (!errors) {
                Summary summary = new Summary(Trees.instance(task), source);
                for (CompilationUnitTree cu : units) {
                    summary.unit = cu;
                    summary.scan(cu, null);
                }
                json.append(",\"summary\":{\"methods\":").append(array(summary.methods))
                        .append(",\"calls\":").append(array(summary.calls))
                        .append(",\"switchTypes\":").append(array(summary.switches)).append('}');
            }
        } catch (Throwable t) {
            json.append("\"internalError\":").append(str(t.toString()));
        }
        return json.append('}').toString();
    }

    static String typeName(String source) {
        Matcher m = PUBLIC_TYPE.matcher(source);
        if (m.find()) return m.group(1);
        m = ANY_TYPE.matcher(source);
        return m.find() ? m.group(1) : "Main";
    }

    /** Method signatures, resolved call targets and switch-expression types from the attributed trees. */
    static final class Summary extends TreePathScanner<Void, Void> {
        final Trees trees;
        final String source;
        final SourcePositions positions;
        CompilationUnitTree unit;
        final List<String> methods = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final List<String> switches = new ArrayList<>();

        Summary(Trees trees, String source) {
            this.trees = trees;
            this.source = source;
            this.positions = trees.getSourcePositions();
        }

        String text(Tree t) {
            long s = positions.getStartPosition(unit, t);
            long e = positions.getEndPosition(unit, t);
            return s < 0 || e < 0 ? null : source.substring((int) s, (int) e).replaceAll("\\s+", " ");
        }

        static String signature(Element e) {
            if (!(e instanceof ExecutableElement ex)) return String.valueOf(e);
            List<String> params = new ArrayList<>();
            ex.getParameters().forEach(p -> params.add(p.asType().toString()));
            return e.getEnclosingElement() + "." + e.getSimpleName() + "(" + String.join(", ", params) + ") : "
                    + ex.getReturnType();
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            Element e = trees.getElement(getCurrentPath());
            if (e != null && positions.getStartPosition(unit, node) >= 0 && text(node.getBody()) != null) {
                methods.add(signature(e));
            }
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            String t = text(node);
            if (t != null) {
                calls.add(t + "  ->  " + signature(trees.getElement(getCurrentPath())));
            }
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
            String t = text(node.getExpression());
            switches.add("switch " + t + "  :  " + trees.getTypeMirror(getCurrentPath()));
            return super.visitSwitchExpression(node, unused);
        }
    }

    static String array(List<String> items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(',');
            b.append(str(items.get(i)));
        }
        return b.append(']').toString();
    }

    static String str(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
