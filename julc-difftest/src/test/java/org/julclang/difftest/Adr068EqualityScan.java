package org.julclang.difftest;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.EqualityOperations;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-068 M0's scan of D6's equality call sites (the {@code JULC0087} predicate, {@link ArrayIdentityEquality}, over
 * the operations of {@link EqualityOperations}), with javac ({@code analyze()}, {@code com.sun.source} trees) on the
 * corpus sources.
 * <p>
 * A unit is scanned in one of three states, reported with each site:
 * <ul>
 *   <li>{@code accepted}: javac reports no error for the unit as written;</li>
 *   <li>{@code accepted+imports}: javac accepts it once the implicit on-demand imports of JuLC's dialect
 *       ({@code org.julclang.ledger.*}, {@code org.julclang.stdlib.*}, {@code org.julclang.stdlib.lib.*}) are added
 *       on the first line (or the package line), as M1's rewrite will (D6); line numbers are unchanged;</li>
 *   <li>{@code partial}: javac rejects the unit (BigInteger operators, say); a site is reported only when javac
 *       attributed its compared type without error.</li>
 * </ul>
 * Generic methods (the conservative approximation documented in the baseline evidence): a comparison whose compared
 * type is one of the enclosing method's type variables is listed as a type-variable site, and each call of that
 * method from the same unit is checked at the type javac binds to that variable, where the variable is the declared
 * type of a parameter (one level; M3 computes the transitive summaries through callees and lambdas).
 */
final class Adr068EqualityScan {

    static final String IMPLICIT_IMPORTS =
            "import org.julclang.ledger.*; import org.julclang.stdlib.*; import org.julclang.stdlib.lib.*; ";

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;");

    /** One reported site. */
    record Site(String corpus, String unit, String file, int line, int column, String operation, String compared,
                String kind, String path, String note) {

        String row() {
            return corpus + "\t" + unit + "\t" + file + ":" + line + ":" + column + "\t" + operation + "\t" + compared
                    + "\t" + kind + "\t" + path + "\t" + note;
        }
    }

    /** One source to scan: a label for the report and its text. */
    record Unit(String label, String text) {
    }

    final List<Site> hits = new ArrayList<>();
    final List<Site> typeVariableSites = new ArrayList<>();
    final List<Site> nestedCollectionSites = new ArrayList<>();
    /** Sites whose compared type javac did not attribute (only in units it rejected): not decided, counted (F204). */
    final List<Site> skippedSites = new ArrayList<>();
    final Map<String, int[]> unitCounts = new LinkedHashMap<>();

    private final String classPath;

    Adr068EqualityScan(String classPath) {
        this.classPath = classPath;
    }

    /** Scans each unit on its own (corpus cases), trying the implicit imports when javac rejects it as written. */
    void scanEach(String corpus, List<Unit> units) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            for (Unit unit : units) {
                var outcome = analyze(files, List.of(unit), false);
                if (!outcome.accepted().getFirst()) {
                    var withImports = analyze(files, List.of(unit), true);
                    if (withImports.accepted().getFirst()) outcome = withImports;
                }
                record(corpus, outcome);
            }
        }
    }

    /** Scans the units together, as one project (the external examples). */
    void scanTogether(String corpus, List<Unit> units) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            record(corpus, analyze(files, units, false));
        }
    }

    private record Outcome(List<Unit> units, List<Boolean> accepted, boolean imports, List<Site> sites,
                           List<Site> typeVariables, List<Site> nested, List<Site> skipped) {
    }

    private void record(String corpus, Outcome outcome) {
        var counts = unitCounts.computeIfAbsent(corpus, k -> new int[3]);
        for (int i = 0; i < outcome.units().size(); i++) {
            counts[outcome.accepted().get(i) ? (outcome.imports() ? 1 : 0) : 2]++;
        }
        for (var s : outcome.sites()) hits.add(withCorpus(s, corpus));
        for (var s : outcome.typeVariables()) typeVariableSites.add(withCorpus(s, corpus));
        for (var s : outcome.nested()) nestedCollectionSites.add(withCorpus(s, corpus));
        for (var s : outcome.skipped()) skippedSites.add(withCorpus(s, corpus));
    }

    private static Site withCorpus(Site s, String corpus) {
        return new Site(corpus, s.unit(), s.file(), s.line(), s.column(), s.operation(), s.compared(), s.kind(),
                s.path(), s.note());
    }

    private Outcome analyze(StandardJavaFileManager files, List<Unit> units, boolean imports) throws IOException {
        var objects = new ArrayList<JavaFileObject>();
        var byUri = new HashMap<URI, Integer>();
        for (int i = 0; i < units.size(); i++) {
            String text = imports ? withImplicitImports(units.get(i).text()) : units.get(i).text();
            URI uri = URI.create("string:///" + fileName(units.get(i), i));
            byUri.put(uri, i);
            objects.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return text;
                }
            });
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, files, diagnostics,
                List.of("-proc:none", "-source", "21", "-Xlint:none", "-implicit:none", "-classpath", classPath),
                null, objects);
        var accepted = new ArrayList<Boolean>();
        for (int i = 0; i < units.size(); i++) accepted.add(true);
        Iterable<? extends CompilationUnitTree> trees;
        try {
            trees = task.parse();
            task.analyze();
        } catch (RuntimeException | AssertionError e) {
            for (int i = 0; i < units.size(); i++) accepted.set(i, false);
            return new Outcome(units, accepted, imports, List.of(), List.of(), List.of(), List.of());
        }
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            Integer index = d.getSource() == null ? null : byUri.get(d.getSource().toUri());
            if (index == null) {
                for (int i = 0; i < units.size(); i++) accepted.set(i, false);
            } else {
                accepted.set(index, false);
            }
        }
        var sites = new ArrayList<Site>();
        var typeVariables = new ArrayList<Site>();
        var nested = new ArrayList<Site>();
        var skipped = new ArrayList<Site>();
        var scanner = new SiteScanner(task, units, byUri, accepted, imports, sites, typeVariables, nested, skipped);
        for (CompilationUnitTree unit : trees) scanner.scanUnit(unit);
        scanner.checkGenericCalls(trees);
        return new Outcome(units, accepted, imports, sites, typeVariables, nested, skipped);
    }

    static String withImplicitImports(String text) {
        var m = PACKAGE.matcher(text);
        if (m.find()) return text.substring(0, m.end()) + " " + IMPLICIT_IMPORTS + text.substring(m.end());
        return IMPLICIT_IMPORTS + text;
    }

    /** The file name javac requires for the unit's public top-level type, or a neutral name. */
    private static String fileName(Unit unit, int index) {
        var m = Pattern.compile("(?m)^\\s*public\\s+(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                + "(?:class|interface|record|enum|@interface)\\s+(\\w+)").matcher(unit.text());
        var pkg = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(unit.text());
        String dir = pkg.find() ? pkg.group(1).replace('.', '/') + "/" : "";
        return dir + (m.find() ? m.group(1) : "Unit" + index) + ".java";
    }

    private static final class SiteScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final List<Unit> units;
        private final Map<URI, Integer> byUri;
        private final List<Boolean> accepted;
        private final boolean imports;
        private final List<Site> sites;
        private final List<Site> typeVariables;
        private final List<Site> nested;
        private final List<Site> skipped;
        private final ArrayIdentityEquality check;
        private final EqualityOperations operations;
        private final Types types;
        /** Per generic method, the type variables it compares directly. */
        private final Map<ExecutableElement, Set<TypeVariable>> summaries = new HashMap<>();
        private CompilationUnitTree current;
        private int currentIndex;

        SiteScanner(JavacTask task, List<Unit> units, Map<URI, Integer> byUri, List<Boolean> accepted, boolean imports,
                    List<Site> sites, List<Site> typeVariables, List<Site> nested, List<Site> skipped) {
            this.trees = Trees.instance(task);
            this.skipped = skipped;
            this.units = units;
            this.byUri = byUri;
            this.accepted = accepted;
            this.imports = imports;
            this.sites = sites;
            this.typeVariables = typeVariables;
            this.nested = nested;
            this.types = task.getTypes();
            var elements = task.getElements();
            this.check = new ArrayIdentityEquality(types, elements, trees);
            this.operations = new EqualityOperations(types, elements);
        }

        void scanUnit(CompilationUnitTree unit) {
            current = unit;
            currentIndex = byUri.getOrDefault(unit.getSourceFile().toUri(), 0);
            scan(new TreePath(unit), null);
        }

        private String state() {
            return accepted.get(currentIndex) ? (imports ? "accepted+imports" : "accepted") : "partial";
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            var path = getCurrentPath();
            var element = trees.getElement(path);
            if (element instanceof ExecutableElement method) {
                TypeMirror receiver = receiver(path, node);
                var arguments = node.getArguments().stream().map(a -> trees.getTypeMirror(new TreePath(path, a))).toList();
                if (arguments.stream().noneMatch(a -> a == null)) {
                    for (var site : operations.sites(method, receiver, arguments)) {
                        if (site.compared() == null || site.compared().getKind() == TypeKind.ERROR) {
                            skip(path, site.operation(), String.valueOf(site.compared()));
                            continue;
                        }
                        report(path, site.operation(), site.compared(), site.julcCollection(), site.collectionOperation(),
                                site.raw() ? "raw collection: compared at the argument's type" : "");
                    }
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        private TypeMirror receiver(TreePath path, MethodInvocationTree node) {
            if (node.getMethodSelect() instanceof MemberSelectTree select) {
                var expression = new TreePath(new TreePath(path, select), select.getExpression());
                var element = trees.getElement(expression);
                if (element instanceof TypeElement) return null; // a static call through the class name
                return trees.getTypeMirror(expression);
            }
            if (node.getMethodSelect() instanceof IdentifierTree) {
                for (TreePath p = path; p != null; p = p.getParentPath()) {
                    if (p.getLeaf() instanceof ClassTree) {
                        var type = trees.getElement(p);
                        return type == null ? null : type.asType();
                    }
                }
            }
            return null;
        }

        private void report(TreePath path, String operation, TypeMirror compared, boolean julcCollection,
                            boolean collectionOperation, String note) {
            var result = check.check(compared, julcCollection);
            var position = position(path.getLeaf());
            var file = units.get(currentIndex).label();
            if (collectionOperation && julcCollection && isJulcCollection(compared)) {
                nested.add(new Site("", state(), file, position[0], position[1], operation, compared.toString(),
                        result.kind().name(), result.path(), "nested JuLC collection compared (#297)"));
            }
            if (!result.rejected()) return;
            // A path through a type javac could not attribute (in a unit it rejected) says nothing: counted apart.
            if (result.path().contains(ArrayIdentityEquality.ERROR_TYPE)) {
                skip(path, operation, compared.toString());
                return;
            }
            if (result.kind() == ArrayIdentityEquality.Kind.TYPE_VARIABLE) {
                var method = enclosingMethod(path);
                if (method != null && compared.getKind() == TypeKind.TYPEVAR
                        && method.getTypeParameters().stream().anyMatch(p -> types.isSameType(p.asType(), compared))) {
                    summaries.computeIfAbsent(method, k -> new HashSet<>()).add((TypeVariable) compared);
                    typeVariables.add(new Site("", state(), file, position[0], position[1], operation,
                            compared.toString(), result.kind().name(), result.path(),
                            "type variable of " + method.getEnclosingElement().getSimpleName() + "." + method.getSimpleName()));
                    return;
                }
            }
            sites.add(new Site("", state(), file, position[0], position[1], operation, compared.toString(),
                    result.kind().name(), result.path(), note));
        }

        private void skip(TreePath path, String operation, String compared) {
            var position = position(path.getLeaf());
            skipped.add(new Site("", state(), units.get(currentIndex).label(), position[0], position[1], operation,
                    compared, "ERROR_TYPE", "", "compared type not attributed"));
        }

        private boolean isJulcCollection(TypeMirror type) {
            if (type.getKind() != TypeKind.DECLARED) return false;
            var element = (TypeElement) types.asElement(type);
            return ArrayIdentityEquality.JULC_COLLECTIONS.contains(element.getQualifiedName().toString());
        }

        private ExecutableElement enclosingMethod(TreePath path) {
            for (TreePath p = path; p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof MethodTree) return (ExecutableElement) trees.getElement(p);
                if (p.getLeaf() instanceof ClassTree) return null;
                if (p.getLeaf() instanceof LambdaExpressionTree) continue;
            }
            return null;
        }

        /** Line and column (a character offset, ADR-068 I7) of the tree's start. */
        private int[] position(Tree tree) {
            long start = trees.getSourcePositions().getStartPosition(current, tree);
            var lines = current.getLineMap();
            int line = (int) lines.getLineNumber(start);
            int column = (int) (start - lines.getStartPosition(line) + 1);
            if (line == 1 && imports) {
                column -= units.get(currentIndex).text().contains("package ") ? 0 : IMPLICIT_IMPORTS.length();
            }
            return new int[]{line, column};
        }

        /** Checks each call of a summarized generic method at the types javac bound to its compared variables. */
        void checkGenericCalls(Iterable<? extends CompilationUnitTree> units) {
            if (summaries.isEmpty()) return;
            for (CompilationUnitTree unit : units) {
                current = unit;
                currentIndex = byUri.getOrDefault(unit.getSourceFile().toUri(), 0);
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        var path = getCurrentPath();
                        if (trees.getElement(path) instanceof ExecutableElement method && summaries.containsKey(method)) {
                            var instantiated = trees.getTypeMirror(new TreePath(path, node.getMethodSelect()));
                            if (instantiated instanceof ExecutableType executable) {
                                var declared = method.getParameters();
                                for (int i = 0; i < declared.size() && i < executable.getParameterTypes().size(); i++) {
                                    var parameter = declared.get(i).asType();
                                    if (parameter.getKind() != TypeKind.TYPEVAR) continue;
                                    if (summaries.get(method).stream().noneMatch(v -> types.isSameType(v, parameter))) continue;
                                    var bound = executable.getParameterTypes().get(i);
                                    if (bound.getKind() == TypeKind.TYPEVAR || bound.getKind() == TypeKind.ERROR) continue;
                                    report(path, method.getSimpleName() + "<" + parameter + ">", bound, false, false,
                                            "bound at a call of " + method.getEnclosingElement().getSimpleName()
                                                    + "." + method.getSimpleName());
                                }
                            }
                        }
                        return super.visitMethodInvocation(node, unused);
                    }
                }.scan(new TreePath(unit), null);
            }
        }
    }

    static List<String> lines(List<Site> sites) {
        var rows = new ArrayList<String>();
        rows.add("corpus\tunit\tsite\toperation\tcompared\tkind\tpath\tnote");
        sites.stream().map(Site::row).distinct().sorted().forEach(rows::add);
        return rows;
    }
}
