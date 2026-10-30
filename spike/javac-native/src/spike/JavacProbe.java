package spike;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Feasibility probe for ADR-065 M1: run javac's parse() + analyze() on an in-memory source and print what a
 * JuLC frontend would need from the attributed trees.
 *
 * Usage: JavacProbe [jrt|embedded] [javactool|toolprovider] [--extract]
 *   jrt       javac's standard file manager (reads the JDK's jrt:/ system modules) with in-memory sources
 *   embedded  a custom JavaFileManager whose only system module is an embedded, minimal java.base
 *             (resource /platform.bin); no jrt:/ and no disk access
 *
 * Lines starting with "TIMING" carry measurements; every other line is deterministic output that is compared
 * across the JVM, native-image and Web Image runs.
 */
public final class JavacProbe {

    static final String OK_SOURCE = """
            import java.math.BigInteger;
            public class Probe {
                sealed interface S permits A, B {}
                record A(BigInteger v) implements S {}
                record B(BigInteger v) implements S {}
                static BigInteger m(S s, BigInteger k) {
                    return switch (s) { case A a when a.v().compareTo(k) > 0 -> a.v().add(k); case A a -> a.v(); case B b -> b.v().negate(); };
                }
            }
            """;

    static final String ERROR_SOURCE = """
            import java.math.BigInteger;
            public class Probe {
                sealed interface S permits A, B {}
                record A(BigInteger v) implements S {}
                record B(BigInteger v) implements S {}
                static BigInteger m(S s, BigInteger k) {
                    return "x";
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        String mode = args.length > 0 ? args[0] : "jrt";
        String toolChoice = args.length > 1 ? args[1] : "javactool";
        boolean extract = List.of(args).contains("--extract");

        JavaCompiler fromToolProvider;
        try {
            fromToolProvider = ToolProvider.getSystemJavaCompiler();
        } catch (Throwable t) {
            fromToolProvider = null;
            System.out.println("ToolProvider.getSystemJavaCompiler() threw " + t);
        }
        System.out.println("ToolProvider.getSystemJavaCompiler() = "
                + (fromToolProvider == null ? "null" : fromToolProvider.getClass().getName()));
        JavaCompiler fromJavacTool = null;
        try {
            fromJavacTool = com.sun.tools.javac.api.JavacTool.create();
        } catch (Throwable t) {
            System.out.println("JavacTool.create() threw " + t);
        }
        System.out.println("JavacTool.create() = "
                + (fromJavacTool == null ? "null" : fromJavacTool.getClass().getName()));

        JavaCompiler compiler = "toolprovider".equals(toolChoice) ? fromToolProvider : fromJavacTool;
        if (compiler == null) {
            System.out.println("FATAL: no compiler for choice " + toolChoice);
            return;
        }
        System.out.println("using " + toolChoice + ", mode " + mode + ", latest source version "
                + SourceVersion.latest());

        long tLoad0 = System.nanoTime();
        JavaFileManager fm;
        EmbeddedFileManager embedded = null;
        if ("embedded".equals(mode)) {
            embedded = EmbeddedFileManager.fromResource("/platform.bin");
            fm = embedded;
            System.out.println("embedded platform: " + embedded.classCount() + " class files");
        } else {
            StandardJavaFileManager standard = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
            fm = standard;
        }
        long tLoad1 = System.nanoTime();

        for (int round = 1; round <= 2; round++) {
            long a0 = System.nanoTime();
            run(compiler, fm, "ok", OK_SOURCE, round == 1, extract && round == 1);
            long a1 = System.nanoTime();
            run(compiler, fm, "error", ERROR_SOURCE, round == 1, false);
            long a2 = System.nanoTime();
            System.out.printf("TIMING round %d: ok-case %.1f ms, error-case %.1f ms%n", round,
                    (a1 - a0) / 1e6, (a2 - a1) / 1e6);
        }
        if (embedded != null && System.getProperty("probe.listOpened") != null) {
            embedded.printOpened(System.getProperty("probe.listOpened"));
        }
        long t1 = System.nanoTime();
        System.out.printf("TIMING platform load %.1f ms; main total %.1f ms%n", (tLoad1 - tLoad0) / 1e6, (t1 - t0) / 1e6);
    }

    static void run(JavaCompiler compiler, JavaFileManager fm, String label, String source, boolean print,
                    boolean extract) throws Exception {
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StringWriter out = new StringWriter();
        List<String> options = List.of("-proc:none", "-Xlint:none");
        JavaFileObject unit = new StringSource("Probe", source);
        JavacTask task = (JavacTask) compiler.getTask(out, fm, diags, options, null, List.of(unit));
        Iterable<? extends CompilationUnitTree> units = task.parse();
        task.analyze();
        if (!print) {
            return;
        }
        System.out.println("== case " + label + " ==");
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            System.out.println("diagnostic " + d.getKind() + " " + d.getLineNumber() + ":" + d.getColumnNumber()
                    + " " + d.getCode() + ": " + d.getMessage(Locale.ROOT));
        }
        if (!out.toString().isBlank()) {
            System.out.println("javac out: " + out.toString().strip());
        }
        if (diags.getDiagnostics().isEmpty()) {
            System.out.println("no diagnostics");
        }
        Trees trees = Trees.instance(task);
        for (CompilationUnitTree cu : units) {
            new Reporter(trees, cu, source, extract).scan(cu, null);
        }
    }

    /** Prints the resolved element of each method call and the type of each switch (and, with --extract, of every expression). */
    static final class Reporter extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final CompilationUnitTree cu;
        private final String source;
        private final boolean extract;
        private final SourcePositions positions;

        Reporter(Trees trees, CompilationUnitTree cu, String source, boolean extract) {
            this.trees = trees;
            this.cu = cu;
            this.source = source;
            this.extract = extract;
            this.positions = trees.getSourcePositions();
        }

        private String text(Tree t) {
            long s = positions.getStartPosition(cu, t);
            long e = positions.getEndPosition(cu, t);
            if (s < 0 || e < 0) return "<no position>";
            return source.substring((int) s, (int) e).replaceAll("\\s+", " ");
        }

        private String describe(Element e) {
            if (e == null) return "null";
            if (e instanceof ExecutableElement ex) {
                List<String> params = new ArrayList<>();
                ex.getParameters().forEach(p -> params.add(p.asType().toString()));
                return e.getKind() + " " + e.getEnclosingElement() + "." + e.getSimpleName()
                        + "(" + String.join(",", params) + ") : " + ex.getReturnType();
            }
            return e.getKind() + " " + e;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            TreePath path = getCurrentPath();
            System.out.println("call `" + text(node) + "` -> " + describe(trees.getElement(path))
                    + " ; type " + trees.getTypeMirror(path));
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
            TypeMirror type = trees.getTypeMirror(getCurrentPath());
            System.out.println("switch expression type: " + type + " (selector type "
                    + trees.getTypeMirror(new TreePath(getCurrentPath(), node.getExpression())) + ")");
            return super.visitSwitchExpression(node, unused);
        }

        @Override
        public Void scan(Tree tree, Void unused) {
            if (extract && tree instanceof ExpressionTree && getCurrentPath() != null) {
                TreePath path = new TreePath(getCurrentPath(), tree);
                Element el = trees.getElement(path);
                System.out.println("  expr " + tree.getKind() + " `" + text(tree) + "` : " + trees.getTypeMirror(path)
                        + (el == null ? "" : " ; element " + describe(el)));
            }
            return super.scan(tree, unused);
        }
    }

    static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String className, String code) {
            super(URI.create("string:///" + className + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
