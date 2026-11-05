package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.pir.TypeMethodRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lowering's three lints (ADR-068 plan), over the sources of {@code lower/} and every package under it, attributed by
 * javac so that what a name resolved to is what is judged and its spelling cannot matter: an import, a qualified name, a
 * {@code var} and a method reference are all one to these. Each has negative controls, which must compile: a lint that
 * cannot fail proves nothing, and a control that does not resolve would pass for that reason.
 * <ol>
 *   <li>{@code lower/} reaches javac only through {@code JavacFacts}: nothing of {@code com.sun.source.util} or
 *       {@code com.sun.tools.javac}, whether written or only inferred. That is the whole package, walkers included:
 *       {@code TreeScanner} walks by kind, which is the job of the one dispatch in {@code Lowering}, and a scan of the
 *       source belongs in {@code subset/}.</li>
 *   <li>Only {@code Terms} builds a term that binds or reads a source name (I13): {@code Var}, {@code Lam}, {@code Let},
 *       {@code LetRec}, {@code Binding}, {@code ListMatch}, {@code PairMatch} and {@code MatchBranch}. Elsewhere only a
 *       reserved {@code #} literal is a name. {@code PirHelpers} builds binders from a caller's name, so {@code lower/}
 *       may call only what {@link #PIR_HELPERS} lists.</li>
 *   <li>No lookup by a name {@code lower/} made up (F870): {@code JavacFacts.elements()} exposes {@code getTypeElement}, a
 *       registry looks up by string, and {@code TypeMethodRegistry.dispatch} guesses from a type's name.</li>
 * </ol>
 */
class JavacLoweringLintTest {

    private static final Path LOWER = Path.of("src/main/java/org/julclang/compiler/frontend/javac/lower");

    private static final String PIR_TERM = "org.julclang.compiler.pir.PirTerm.";
    private static final Set<String> BINDERS = Set.of(PIR_TERM + "Var", PIR_TERM + "Lam", PIR_TERM + "Let", PIR_TERM + "LetRec",
            PIR_TERM + "Binding", PIR_TERM + "ListMatch", PIR_TERM + "PairMatch", PIR_TERM + "MatchBranch");
    /** The binders with a name in their first argument, which a {@code #} literal may be. */
    private static final Set<String> NAMED_FIRST = Set.of(PIR_TERM + "Var", PIR_TERM + "Lam", PIR_TERM + "Let", PIR_TERM + "Binding");
    /** What {@code lower/} may call of {@code PirHelpers}: none of it takes a source name. */
    private static final Set<String> PIR_HELPERS = Set.of("isGeneratedName", "builtinApp2", "swappedInOrder", "equality",
            "fieldExtraction", "wrapEncode", "freeVariables", "hygienicName", "readOnce");

    private static final String FACTS = JavacFacts.class.getName();
    private static final Set<String> LOOKUPS = Set.of(
            "javax.lang.model.util.Elements.getTypeElement", "javax.lang.model.util.Elements.getPackageElement",
            "javax.lang.model.util.Elements.getAllTypeElements", "javax.lang.model.util.Elements.getAllPackageElements",
            "javax.lang.model.util.Elements.getModuleElement", FACTS + ".elements",
            "org.julclang.compiler.resolve.TypeRegistry.namedDefinitions",
            "org.julclang.compiler.resolve.TypeRegistry.snapshot",
            "org.julclang.compiler.pir.TypeMethodRegistry.dispatch");
    /** Lookups fine for an identity, with the name as their first argument. */
    private static final String LOOKUP_NOMINAL = "org.julclang.compiler.resolve.TypeRegistry.lookupNominal";
    private static final String STDLIB_LOOKUP = "org.julclang.compiler.pir.StdlibLookup";
    /** A call target's lookup key, which {@code CallTargets} takes from the owner's identity. */
    private static final Set<String> CALL_TARGET_KEYS = Set.of("org.julclang.compiler.frontend.javac.lower.CallTarget.Keyed.className",
            "org.julclang.compiler.frontend.javac.lower.CallTarget.Library.className");

    /** A source attributed against this test's class path, which holds the compiled compiler. */
    private record Attributed(Trees trees, Types types, Elements elements, List<TreePath> units) {
    }

    private static Attributed attribute(List<JavaFileObject> files) throws IOException {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null, diagnostics,
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path"),
                        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED"), null, files);
        var units = new ArrayList<TreePath>();
        task.parse().forEach(unit -> units.add(new TreePath(unit)));
        task.analyze();
        var errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(Object::toString).toList();
        assertEquals(List.of(), errors, "a linted source must compile: an unresolved name proves nothing");
        return new Attributed(Trees.instance(task), task.getTypes(), task.getElements(), units);
    }

    private static JavaFileObject text(String name, String source) {
        return new SimpleJavaFileObject(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    /** A control: a source in the package of {@code lower/}. */
    private static Attributed control(String source) throws IOException {
        return attribute(List.of(text("Names.java", "package org.julclang.compiler.frontend.javac.lower;\n" + source)));
    }

    private static List<Path> sources(Path root) throws IOException {
        try (Stream<Path> sources = Files.walk(root)) {
            return sources.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static Attributed lowering() throws IOException {
        var files = new ArrayList<JavaFileObject>();
        for (Path source : sources(LOWER)) {
            files.add(text(LOWER.relativize(source).toString(), Files.readString(source)));
        }
        return attribute(files);
    }

    /** The walk of one lint: what each tree in a unit resolved to. */
    private abstract static class Lint extends TreePathScanner<Void, Void> {
        final Trees trees;
        final List<String> violations = new ArrayList<>();

        Lint(Trees trees) {
            this.trees = trees;
        }

        String file() {
            return Path.of(getCurrentPath().getCompilationUnit().getSourceFile().getName()).getFileName().toString();
        }

        void violation(Tree tree) {
            violations.add(file() + ": " + tree);
        }

        Element element(Tree tree) {
            return trees.getElement(new TreePath(getCurrentPath(), tree));
        }

        List<String> run(List<TreePath> units) {
            units.forEach(unit -> scan(unit, null));
            return violations;
        }
    }

    private static String qualified(Element type) {
        return type instanceof TypeElement element ? element.getQualifiedName().toString() : "";
    }

    private static String member(ExecutableElement method) {
        return qualified(method.getEnclosingElement()) + "." + method.getSimpleName();
    }

    // --- Lint 1 ---

    private static boolean isJavacInternal(String name) {
        return name.startsWith("com.sun.source.util.") || name.startsWith("com.sun.tools.javac.");
    }

    private static boolean isJavacInternal(TypeMirror type) {
        return type instanceof DeclaredType declared && isJavacInternal(qualified(declared.asElement()));
    }

    /** What is written or inferred of javac past {@code JavacFacts}, at any tree: an import, a name, a {@code var}. */
    private static List<String> reachesJavac(Attributed source) {
        return new Lint(source.trees()) {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree == null) return null;
                var path = new TreePath(getCurrentPath(), tree);
                var element = source.trees().getElement(path);
                if (element != null && element.getKind() == ElementKind.PACKAGE
                        && (element.toString().equals("com.sun.source.util") || element.toString().startsWith("com.sun.tools.javac"))) {
                    violation(tree);
                }
                if (element instanceof TypeElement type && isJavacInternal(qualified(type))) violation(tree);
                if (tree instanceof ExpressionTree && isJavacInternal(source.trees().getTypeMirror(path))) violation(tree);
                return super.scan(tree, unused);
            }
        }.run(source.units());
    }

    // --- Lint 2 ---

    /** A binder's constructor or {@code PirHelpers} method outside {@code Terms}, however it is reached. */
    private static List<String> buildsBinders(Attributed source, int[] allowed) {
        return new Lint(source.trees()) {
            @Override
            public Void visitNewClass(NewClassTree tree, Void unused) {
                String type = qualified(((ExecutableElement) trees.getElement(getCurrentPath())).getEnclosingElement());
                if (BINDERS.contains(type)) {
                    if (file().equals("Terms.java")) allowed[0]++;
                    else if (!(NAMED_FIRST.contains(type) && generated(tree.getArguments().getFirst()))) violation(tree);
                }
                return super.visitNewClass(tree, null);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
                if (trees.getElement(getCurrentPath()) instanceof ExecutableElement method) {
                    boolean constructs = method.getKind() == ElementKind.CONSTRUCTOR
                            && BINDERS.contains(qualified(method.getEnclosingElement()));
                    if (constructs || isHelper(method)) violation(tree);
                }
                return super.visitMemberReference(tree, null);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                if (trees.getElement(new TreePath(getCurrentPath(), tree.getMethodSelect())) instanceof ExecutableElement method
                        && (isHelper(method) && !(file().equals("Terms.java") && isDecode(method))
                        || namesAGeneratedBinder(method) && !generated(tree.getArguments().getFirst()))) {
                    violation(tree);
                }
                return super.visitMethodInvocation(tree, null);
            }
        }.run(source.units());
    }

    /** {@code hygienicName} takes the name it makes one from, which must be reserved where it is written. */
    private static boolean namesAGeneratedBinder(ExecutableElement method) {
        return qualified(method.getEnclosingElement()).equals("org.julclang.compiler.pir.PirHelpers")
                && method.getSimpleName().contentEquals("hygienicName");
    }

    private static boolean isHelper(ExecutableElement method) {
        return qualified(method.getEnclosingElement()).equals("org.julclang.compiler.pir.PirHelpers")
                && !PIR_HELPERS.contains(method.getSimpleName().toString());
    }

    /** A decoded binder takes a read of the binder under it, so a decode is {@code Terms}' alone, as a binder is. */
    private static boolean isDecode(ExecutableElement method) {
        return method.getSimpleName().contentEquals("wrapDecode");
    }

    private static boolean generated(ExpressionTree name) {
        return name instanceof LiteralTree literal && literal.getValue() instanceof String s && s.startsWith("#");
    }

    // --- Lint 3 ---

    /** A lookup by name, called or referred to. */
    private static List<String> looksUpByName(Attributed source) {
        var stdlib = source.types().erasure(source.elements().getTypeElement(STDLIB_LOOKUP).asType());
        return new Lint(source.trees()) {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                if (trees.getElement(new TreePath(getCurrentPath(), tree.getMethodSelect())) instanceof ExecutableElement method) {
                    if (LOOKUPS.contains(member(method))) violation(tree);
                    else if (takesAnIdentity(method) && !isIdentity(firstString(tree))) violation(tree);
                }
                return super.visitMethodInvocation(tree, null);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
                if (trees.getElement(getCurrentPath()) instanceof ExecutableElement method
                        && (LOOKUPS.contains(member(method)) || takesAnIdentity(method))) {
                    violation(tree);
                }
                return super.visitMemberReference(tree, null);
            }

            /** A lookup's name: the arguments before it, such as a compilation's context, are no name. */
            private ExpressionTree firstString(MethodInvocationTree tree) {
                for (ExpressionTree argument : tree.getArguments()) {
                    var type = trees.getTypeMirror(new TreePath(getCurrentPath(), argument));
                    if (type instanceof DeclaredType declared && qualified(declared.asElement()).equals("java.lang.String")) return argument;
                }
                return tree.getArguments().getFirst();
            }

            /** A string taken from an {@code Element}: {@code facts.identity(...)}, or a call target's key made from it. */
            private boolean isIdentity(ExpressionTree argument) {
                return argument instanceof MethodInvocationTree call
                        && trees.getElement(new TreePath(new TreePath(getCurrentPath(), argument), call.getMethodSelect()))
                                instanceof ExecutableElement method
                        && (member(method).equals(FACTS + ".identity") || CALL_TARGET_KEYS.contains(member(method)));
            }

            /** {@code lookupNominal}, or {@code lookup} of a {@code StdlibLookup}, also through a type that implements it. */
            private boolean takesAnIdentity(ExecutableElement method) {
                return member(method).equals(LOOKUP_NOMINAL) || method.getSimpleName().contentEquals("lookup")
                        && source.types().isSubtype(source.types().erasure(method.getEnclosingElement().asType()), stdlib);
            }
        }.run(source.units());
    }

    // --- The lints over lower/ ---

    @Test
    void lowerReachesJavacOnlyThroughFacts() throws IOException {
        assertEquals(List.of(), reachesJavac(lowering()));
    }

    @Test
    void onlyTermsBuildsASourceBinder() throws IOException {
        int[] allowed = {0};
        assertEquals(List.of(), buildsBinders(lowering(), allowed));
        assertTrue(allowed[0] >= 2, "Terms is where the binders are built: " + allowed[0]);
    }

    @Test
    void nothingInLowerLooksUpAnInventedName() throws IOException {
        assertEquals(List.of(), looksUpByName(lowering()));
    }

    /** A subpackage of {@code lower/} is linted too. */
    @Test
    void aSourceInASubpackageIsRead(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("family"));
        Files.writeString(root.resolve("family/Nested.java"), "class Nested { }");
        assertEquals(1, sources(root).size());
        assertTrue(sources(LOWER).stream().anyMatch(path -> path.endsWith("Terms.java")));
    }

    /** {@code JavacFacts} is the seam: nothing it offers hands out what lint 1 keeps out of {@code lower/}. */
    @Test
    void noPublicFactHandsOutJavacsTaskOrTrees() {
        var banned = Set.of("com.sun.source.util.Trees", "com.sun.source.util.TreePath", "com.sun.source.util.JavacTask",
                "com.sun.source.util.SourcePositions", "com.sun.source.util.DocTrees");
        for (var method : JavacFacts.class.getMethods()) {
            assertFalse(banned.contains(method.getReturnType().getName()), method.toString());
        }
        for (var field : JavacFacts.class.getDeclaredFields()) {
            assertFalse(Modifier.isPublic(field.getModifiers()), field.toString());
        }
    }

    /** F847: nothing in the lowering can keep a program past the compilation that made it. */
    @Test
    void noLoweringClassHoldsAProgram() throws IOException {
        var offenders = new ArrayList<String>();
        for (Path source : sources(LOWER)) {
            if (Files.readString(source).contains("JavacProgram")) offenders.add(source.toString());
        }
        assertEquals(List.of(), offenders);
    }

    // --- Negative controls ---

    @Test
    void lintOneCatchesWhatItSays() throws IOException {
        for (String source : Map.of(
                "import", "import com.sun.source.util.Trees; class A { Trees t; }",
                "star import", "import com.sun.source.util.*; class A { JavacTask t; }",
                "qualified", "class A { Object o = com.sun.source.util.TreePath.class; }",
                "internal", "class A { Object o = com.sun.tools.javac.tree.JCTree.class; }",
                "a walker", "class A extends com.sun.source.util.TreeScanner<Void, Void> { }",
                "inferred", """
                        class A {
                            static com.sun.source.util.SourcePositions held;
                            void v() { var t = B.positions(); t.hashCode(); }
                        }
                        class B { static com.sun.source.util.SourcePositions positions() { return null; } }
                        """).values()) {
            assertFalse(reachesJavac(control(source)).isEmpty(), source);
        }
        assertFalse(reachesJavac(control("""
                class A { void v() { var t = B.positions(); t.hashCode(); } }
                """ + "class B { static com.sun.source.util.SourcePositions positions() { return null; } }")).stream()
                .noneMatch(found -> found.contains("t.hashCode") || found.contains(": t")), "a var is judged by its type");
        assertTrue(reachesJavac(control("import com.sun.source.tree.Tree; class A { Tree t; }")).isEmpty());
    }

    private static final String TERMS_IMPORTS = """
            import org.julclang.compiler.pir.PirHelpers;
            import org.julclang.compiler.pir.PirTerm;
            import org.julclang.compiler.pir.PirType;
            import java.util.List;
            import java.util.function.BiFunction;
            """;

    @Test
    void lintTwoCatchesWhatItSays() throws IOException {
        int[] allowed = {0};
        for (String method : List.of(
                "Object v(String n, PirType t) { return new PirTerm.Var(n, t); }",
                "Object v(String n, PirTerm b) { return new PirTerm.Let(n, b, b); }",
                "Object v(String n, PirType t, PirTerm b) { return new PirTerm.Lam(n, t, b); }",
                "Object v(String n, PirType t) { return new org.julclang.compiler.pir.PirTerm.Var(n, t); }",
                "Object v(String n, PirTerm b) { return new PirTerm.Binding(n, b); }",
                "Object v(String n, PirTerm b) { return new PirTerm.LetRec(List.of(), b); }",
                "Object v(String n, PirTerm b) { return new PirTerm.ListMatch(b, n, n, b, b); }",
                "Object v(String n, PirTerm b, PirType.PairType t) { return new PirTerm.PairMatch(b, t, n, n, b); }",
                "Object v(String n, PirTerm b) { return new PirTerm.MatchBranch(n, List.of(n), List.of(), b, null); }",
                "BiFunction<String, PirType, PirTerm.Var> v() { return PirTerm.Var::new; }",
                "Object v() { BiFunction<List<org.julclang.compiler.pir.PirTerm.Binding>, PirTerm, PirTerm> f = PirTerm.LetRec::new; return f; }",
                "Object v(PirTerm b) { return PirHelpers.wrapDecode(b, null); }",
                "Object v(String n) { return PirHelpers.hygienicName(n, java.util.Set.of()); }")) {
            assertEquals(1, buildsBinders(control(TERMS_IMPORTS + "class A { " + method + " }"), allowed).size(), method);
        }
        assertEquals(1, buildsBinders(control("import org.julclang.compiler.pir.PirTerm.Var;\n"
                + "class A { Object v(String n) { return new Var(n, null); } }"), allowed).size(), "an import of the type");
        assertEquals(1, buildsBinders(control("import org.julclang.compiler.pir.PirTerm.*;\n"
                + "class A { Object v(String n) { return new Var(n, null); } }"), allowed).size(), "a star import");
        assertTrue(buildsBinders(control(TERMS_IMPORTS + "class A { Object v(PirTerm b) { return new PirTerm.Let(\"#tmp\", b, b); } }"),
                allowed).isEmpty());
        assertTrue(buildsBinders(control(TERMS_IMPORTS + "class A { boolean v(String n) { return PirHelpers.isGeneratedName(n); } }"),
                allowed).isEmpty());
        assertTrue(buildsBinders(control(TERMS_IMPORTS + "class A { Object v() { return PirHelpers.hygienicName(\"#tmp\", java.util.Set.of()); } }"),
                allowed).isEmpty());
    }

    private static final String LOOKUP_IMPORTS = """
            import org.julclang.compiler.CompilationContext;
            import org.julclang.compiler.frontend.javac.JavacFacts;
            import org.julclang.compiler.pir.StdlibLookup;
            import org.julclang.compiler.pir.TypeMethodRegistry;
            import org.julclang.compiler.resolve.TypeRegistry;
            import javax.lang.model.element.TypeElement;
            import javax.lang.model.util.Elements;
            import java.util.List;
            import java.util.function.Function;
            import java.util.function.Supplier;
            """;

    @Test
    void lintThreeCatchesWhatItSays() throws IOException {
        for (String method : List.of(
                "Object v(JavacFacts f) { return f.elements(); }",
                "Supplier<?> v(JavacFacts f) { return f::elements; }",
                "Object v(Elements e) { return e.getTypeElement(\"x\"); }",
                "Function<?, ?> v(Elements e) { Function<CharSequence, TypeElement> g = e::getTypeElement; return g; }",
                "Object v(TypeRegistry r) { return r.lookupNominal(\"org.julclang.ledger.Value\"); }",
                "Object v(TypeRegistry r, String n) { return r.lookupNominal(n + \"x\"); }",
                "Function<?, ?> v(TypeRegistry r) { Function<String, ?> g = r::lookupNominal; return g; }",
                "Object v(TypeRegistry r) { return r.namedDefinitions().get(\"Value\"); }",
                "Object v(TypeRegistry r) { return r.snapshot(); }")) {
            assertEquals(1, looksUpByName(control(LOOKUP_IMPORTS + "class A { " + method + " }")).size(), method);
        }
        assertEquals(1, looksUpByName(control(LOOKUP_IMPORTS + "class A { Object v(StdlibLookup l) { "
                + "return l.lookup(\"Builtins\", \"x\", List.of()); } }")).size(), "a stdlib lookup by a made-up name");
        assertEquals(1, looksUpByName(control(LOOKUP_IMPORTS + "class A { Object v(StdlibLookup l, CompilationContext c) { "
                + "return l.lookup(c, \"Builtins\", \"x\", List.of(), List.of()); } }")).size(), "the same, past a context");
        assertEquals(1, looksUpByName(control(LOOKUP_IMPORTS + "class A { Object v(StdlibLookup l, String n) { "
                + "return l.lookup(n, \"x\", List.of()); } }")).size(), "a name from nowhere");
        assertTrue(looksUpByName(control(LOOKUP_IMPORTS + "class A { Object v(JavacFacts f, TypeRegistry r, TypeElement e) { "
                + "return r.lookupNominal(f.identity(e)); } }")).isEmpty());
    }

    /** {@code dispatch} guesses a method from a type's name: the calls slice needs this one most. */
    @Test
    void lintThreeCatchesTheTypeMethodDispatch() throws IOException {
        var method = Arrays.stream(TypeMethodRegistry.class.getMethods())
                .filter(found -> found.getName().equals("dispatch")).findFirst().orElseThrow();
        var arguments = new ArrayList<String>();
        for (var parameter : method.getParameterTypes()) arguments.add("(" + parameter.getCanonicalName() + ") null");
        assertEquals(1, looksUpByName(control(LOOKUP_IMPORTS + "class A { Object v(TypeMethodRegistry r) { return r.dispatch("
                + String.join(", ", arguments) + "); } }")).size());
    }
}
