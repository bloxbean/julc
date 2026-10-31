package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lowering's three lints (ADR-068 plan), read from the sources with javac's parser, in the style of
 * {@code GeneratedBinderNameLintTest}. Each has a negative control: a lint that cannot fail proves nothing.
 * <ol>
 *   <li>{@code lower/} reaches javac only through {@code JavacFacts}: no {@code Trees} or {@code JavacTask}.</li>
 *   <li>Only {@code Terms} builds a {@code Var}, {@code Lam} or {@code Let} for a source name (I13).</li>
 *   <li>No lookup by a name {@code lower/} made up (F870): {@code JavacFacts.elements()} still exposes
 *       {@code getTypeElement}, so this lint is what keeps identity honest.</li>
 * </ol>
 */
class JavacLoweringLintTest {

    private static final Path LOWER = Path.of("src/main/java/org/julclang/compiler/frontend/javac/lower");

    private static final Set<String> FORBIDDEN_TYPES = Set.of("Trees", "JavacTask", "TreePath", "SourcePositions", "DocTrees");
    private static final Set<String> BINDERS = Set.of("PirTerm.Var", "PirTerm.Lam", "PirTerm.Let");

    private static List<CompilationUnitTree> parse(List<JavaFileObject> files) throws IOException {
        var units = new ArrayList<CompilationUnitTree>();
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null, d -> { }, List.of("-proc:none"), null, files);
        task.parse().forEach(units::add);
        return units;
    }

    private static JavaFileObject text(String name, String source) {
        return new SimpleJavaFileObject(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    private static List<CompilationUnitTree> lowering() throws IOException {
        var files = new ArrayList<JavaFileObject>();
        try (Stream<Path> sources = Files.list(LOWER)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                files.add(text(source.getFileName().toString(), Files.readString(source)));
            }
        }
        return parse(files);
    }

    private static String file(CompilationUnitTree unit) {
        return Path.of(unit.getSourceFile().getName()).getFileName().toString();
    }

    /** Lint 1: what an import or a qualified name of {@code lower/} reaches past {@code JavacFacts}. */
    private static List<String> reachesJavac(List<CompilationUnitTree> units) {
        var violations = new ArrayList<String>();
        for (var unit : units) {
            for (ImportTree tree : unit.getImports()) {
                String imported = tree.getQualifiedIdentifier().toString();
                String simple = imported.substring(imported.lastIndexOf('.') + 1);
                if (FORBIDDEN_TYPES.contains(simple) || imported.startsWith("com.sun.tools.javac")) {
                    violations.add(file(unit) + ": import " + imported);
                }
            }
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                    if (tree.toString().startsWith("com.sun.source.util.") || tree.toString().startsWith("com.sun.tools.javac")) {
                        violations.add(file(unit) + ": " + tree);
                    }
                    return null;
                }

                @Override
                public Void visitIdentifier(IdentifierTree tree, Void unused) {
                    if (FORBIDDEN_TYPES.contains(tree.getName().toString())) violations.add(file(unit) + ": " + tree);
                    return null;
                }
            }.scan(unit, null);
        }
        return violations;
    }

    /** Lint 2: a binder constructor outside {@code Terms}, unless its name is a reserved {@code #} literal. */
    private static List<String> buildsBinders(List<CompilationUnitTree> units, int[] allowed) {
        var violations = new ArrayList<String>();
        for (var unit : units) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitNewClass(NewClassTree tree, Void unused) {
                    if (BINDERS.contains(tree.getIdentifier().toString())) {
                        if (file(unit).equals("Terms.java")) allowed[0]++;
                        else if (!generated(tree.getArguments().getFirst())) violations.add(file(unit) + ": " + tree);
                    }
                    return super.visitNewClass(tree, null);
                }
            }.scan(unit, null);
        }
        return violations;
    }

    private static boolean generated(ExpressionTree name) {
        return name instanceof LiteralTree literal && literal.getValue() instanceof String s && s.startsWith("#");
    }

    /** Lint 3: {@code getTypeElement}, {@code elements()}, or a nominal lookup of anything but an identity. */
    private static List<String> looksUpByName(List<CompilationUnitTree> units) {
        var violations = new ArrayList<String>();
        for (var unit : units) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                    String name = tree.getMethodSelect() instanceof MemberSelectTree select
                            ? select.getIdentifier().toString() : tree.getMethodSelect().toString();
                    switch (name) {
                        case "getTypeElement", "getPackageElement", "getAllTypeElements", "elements" ->
                                violations.add(file(unit) + ": " + tree);
                        case "lookupNominal" -> {
                            if (!isIdentity(tree.getArguments().getFirst())) violations.add(file(unit) + ": " + tree);
                        }
                        default -> {
                        }
                    }
                    return super.visitMethodInvocation(tree, null);
                }
            }.scan(unit, null);
        }
        return violations;
    }

    /** A string taken from an {@code Element}: {@code facts.identity(...)}. */
    private static boolean isIdentity(ExpressionTree argument) {
        return argument instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree select
                && select.getIdentifier().contentEquals("identity");
    }

    @Test
    void lowerReachesJavacOnlyThroughFacts() throws IOException {
        assertEquals(List.of(), reachesJavac(lowering()));
    }

    @Test
    void lintOneCatchesWhatItSays() throws IOException {
        assertFalse(reachesJavac(parse(List.of(text("A.java", "import com.sun.source.util.Trees; class A { }")))).isEmpty());
        assertFalse(reachesJavac(parse(List.of(text("A.java", "import com.sun.source.util.JavacTask; class A { }")))).isEmpty());
        assertFalse(reachesJavac(parse(List.of(text("A.java", "class A { Object o = com.sun.source.util.TreePath.class; }")))).isEmpty());
        assertFalse(reachesJavac(parse(List.of(text("A.java", "class A { Object o = com.sun.tools.javac.tree.JCTree.class; }")))).isEmpty());
        assertTrue(reachesJavac(parse(List.of(text("A.java", "import com.sun.source.tree.Tree; class A { }")))).isEmpty());
    }

    @Test
    void onlyTermsBuildsASourceBinder() throws IOException {
        int[] allowed = {0};
        assertEquals(List.of(), buildsBinders(lowering(), allowed));
        assertTrue(allowed[0] >= 2, "Terms is where the binders are built: " + allowed[0]);
    }

    @Test
    void lintTwoCatchesWhatItSays() throws IOException {
        int[] allowed = {0};
        assertEquals(1, buildsBinders(parse(List.of(text("Names.java",
                "class A { Object v(Object b) { return new PirTerm.Var(b.name(), b.type()); } }"))), allowed).size());
        assertEquals(1, buildsBinders(parse(List.of(text("Names.java",
                "class A { Object v(String n) { return new PirTerm.Let(n, null, null); } }"))), allowed).size());
        assertEquals(1, buildsBinders(parse(List.of(text("Names.java",
                "class A { Object v(String n) { return new PirTerm.Lam(n, null, null); } }"))), allowed).size());
        assertTrue(buildsBinders(parse(List.of(text("Names.java",
                "class A { Object v() { return new PirTerm.Let(\"#tmp\", null, null); } }"))), allowed).isEmpty());
    }

    @Test
    void nothingInLowerLooksUpAnInventedName() throws IOException {
        assertEquals(List.of(), looksUpByName(lowering()));
    }

    @Test
    void lintThreeCatchesWhatItSays() throws IOException {
        assertEquals(1, looksUpByName(parse(List.of(text("A.java",
                "class A { Object v(F f) { return f.elements().hashCode(); } }")))).size());
        assertEquals(2, looksUpByName(parse(List.of(text("A.java",
                "class A { Object v(F f) { return f.elements().getTypeElement(\"x\"); } }")))).size());
        assertEquals(1, looksUpByName(parse(List.of(text("A.java",
                "class A { Object v(F f) { return f.registry().lookupNominal(\"org.julclang.ledger.Value\"); } }")))).size());
        assertEquals(1, looksUpByName(parse(List.of(text("A.java",
                "class A { Object v(F f, String n) { return f.registry().lookupNominal(n + \"x\"); } }")))).size());
        assertTrue(looksUpByName(parse(List.of(text("A.java",
                "class A { Object v(F f, Object e) { return f.registry().lookupNominal(f.identity(e)); } }")))).isEmpty());
    }

    /** F847: nothing in the lowering can keep a program past the compilation that made it. */
    @Test
    void noLoweringClassHoldsAProgram() throws IOException {
        var offenders = new ArrayList<String>();
        try (Stream<Path> sources = Files.list(LOWER)) {
            for (Path source : sources.toList()) {
                if (Files.readString(source).contains("JavacProgram")) offenders.add(source.toString());
            }
        }
        assertEquals(List.of(), offenders);
    }
}
