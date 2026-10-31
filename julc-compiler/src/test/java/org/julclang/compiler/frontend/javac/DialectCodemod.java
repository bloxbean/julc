package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.julclang.compiler.corpus.CorpusRecorder;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ADR-068 M1b's dialect codemod (test tooling, not shipped). It rewrites JuLC's dialect beyond Java (D6) into Java
 * that javac accepts with the same meaning, driven by javac's own diagnostics and attributed trees, in the closed
 * environment of the javac gate ({@link JavacFrontend}):
 * <ul>
 *   <li>implicit imports of JuLC's API and of {@code java.math.BigInteger}/{@code java.util} become explicit;</li>
 *   <li>{@code BigInteger} operators become {@code add}/{@code subtract}/{@code multiply}/{@code divide}/
 *       {@code remainder}, {@code compareTo(...) OP 0} and {@code equals};</li>
 *   <li>{@code int}/{@code long} values where a {@code BigInteger} is expected become {@code BigInteger.valueOf} or
 *       {@code ZERO}/{@code ONE}/{@code TWO}/{@code TEN}, and the reverse {@code longValue()}/{@code intValue()};</li>
 *   <li>{@code @NewType} {@code X.of(v)} becomes {@code new X(v)};</li>
 *   <li>a field or method read from a static context becomes {@code static}.</li>
 * </ul>
 * Each round analyzes the units, applies the innermost non-overlapping fixes and analyzes again, until no fix
 * applies. Anything else is left to a person.
 * <p>
 * {@link #main} drives it over the javac gate's report ({@link JavacGateReport}): it indexes every string literal of
 * the repository's Java files and every on-disk {@code .java} file by the digest the report names sources by, runs
 * the codemod on each rejected case (roots and pool, as the gate saw them), and writes each rewrite back into the
 * literal or file it came from.
 */
public final class DialectCodemod {

    private static final Pattern TO_BIG = Pattern.compile("\\b(int|long|short|byte) cannot be converted to java\\.math\\.BigInteger");
    private static final Pattern FROM_BIG = Pattern.compile("java\\.math\\.BigInteger cannot be converted to (int|long)\\b");
    private static final Pattern SYMBOL = Pattern.compile("symbol:\\s+(class|variable)\\s+([\\w$]+)");
    private static final Pattern METHOD_SYMBOL = Pattern.compile("symbol:\\s+method\\s+(\\w+)\\(");
    private static final Pattern DATA_CONVERSION = Pattern.compile(
            "incompatible types: ([\\w.$]+)(?:<.*?>)? cannot be converted to ([\\w.$\\[\\]]+)");
    private static final Pattern UTIL_VARIABLE = Pattern.compile("location: variable (\\w+) of type java\\.util\\.(?:List|Map)<");
    private static final Pattern TO_UTIL = Pattern.compile("cannot be converted to java\\.util\\.(?:List|Map)<");
    private static final Pattern NON_STATIC = Pattern.compile("non-static (variable|method) (\\w+)");

    private final JavacPlatform platform = JavacPlatform.shared();
    private final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    private final JavacFrontend.BundledSources bundled = JavacFrontend.BundledSources.load();
    private final Map<String, String> apiImports;

    public DialectCodemod() {
        apiImports = apiImports(platform);
    }

    /** The codemod's result: the rewritten texts (roots, then pool) and the diagnostics left. */
    public record Result(List<String> roots, List<String> pool, List<String> remaining, int rounds, List<String> rules) {
        public boolean changed(List<String> rootsBefore, List<String> poolBefore) {
            return !roots.equals(rootsBefore) || !pool.equals(poolBefore);
        }
    }

    private record Edit(int start, int end, String text, String rule) {
    }

    /** One analysis: the units by index (roots first), their trees and javac's errors. */
    private final class Analysis {
        final List<String> texts;
        final int rootCount;
        final Map<URI, Integer> unitOf = new HashMap<>();
        final Map<Integer, CompilationUnitTree> trees = new HashMap<>();
        final List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        final Map<String, String> declared = new HashMap<>();   // simple name -> FQCN declared by a unit
        Trees javacTrees;
        SourcePositions positions;

        Analysis(List<String> roots, List<String> pool) {
            texts = new ArrayList<>(roots);
            texts.addAll(pool);
            rootCount = roots.size();
            var infos = JavacUnits.infos(javac, platform, texts);
            var rootFiles = new ArrayList<JavacSourceFile>();
            var poolFiles = new ArrayList<JavacSourceFile>();
            var names = new HashSet<String>();
            for (int i = 0; i < texts.size(); i++) {
                var file = JavacSourceFile.ofText(texts.get(i), infos.get(i));
                for (String name : file.topLevelNames()) {
                    declared.putIfAbsent(name.substring(name.lastIndexOf('.') + 1), name);
                }
                if (i < rootCount) {
                    rootFiles.add(file);
                    names.addAll(file.topLevelNames());
                    unitOf.put(file.toUri(), i);
                } else if (names.add(file.binaryName())) {
                    poolFiles.add(file);
                    unitOf.put(file.toUri(), i);
                }
            }
            var bundledTexts = new ArrayList<>(bundled.texts().values());
            var bundledInfos = JavacUnits.infos(javac, platform, bundledTexts);
            for (int i = 0; i < bundledTexts.size(); i++) {
                var file = JavacSourceFile.ofText(bundledTexts.get(i), bundledInfos.get(i));
                if (names.add(file.binaryName())) poolFiles.add(file);
            }
            var fileManager = new EmbeddedFileManager(platform, poolFiles, null);
            var collector = new DiagnosticCollector<JavaFileObject>();
            var task = (JavacTask) javac.getTask(null, fileManager, collector, JavacFrontend.OPTIONS, null, rootFiles);
            task.setLocale(Locale.ENGLISH);
            task.addTaskListener(new TaskListener() {
                @Override
                public void finished(TaskEvent e) {
                    if (e.getKind() == TaskEvent.Kind.PARSE) {
                        Integer unit = e.getSourceFile() == null ? null : unitOf.get(e.getSourceFile().toUri());
                        if (unit != null) trees.put(unit, e.getCompilationUnit());
                    }
                }
            });
            try {
                task.analyze();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (RuntimeException | StackOverflowError e) {
                // javac itself failed; leave the case to a person.
            }
            javacTrees = Trees.instance(task);
            positions = javacTrees.getSourcePositions();
            for (var d : collector.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d);
            }
        }

        Integer unit(Diagnostic<? extends JavaFileObject> d) {
            return d.getSource() == null ? null : unitOf.get(d.getSource().toUri());
        }

        String text(int unit, Tree tree) {
            var cu = trees.get(unit);
            return texts.get(unit).substring((int) positions.getStartPosition(cu, tree),
                    (int) positions.getEndPosition(cu, tree));
        }

        long start(int unit, Tree tree) {
            return positions.getStartPosition(trees.get(unit), tree);
        }

        long end(int unit, Tree tree) {
            return positions.getEndPosition(trees.get(unit), tree);
        }

        /** The outermost tree with exactly these extents and an accepted kind, with its path. */
        TreePath find(int unit, long start, long end, Set<Tree.Kind> kinds) {
            var cu = trees.get(unit);
            if (cu == null) return null;
            TreePath[] found = new TreePath[1];
            new TreePathScanner<Void, Void>() {
                @Override
                public Void scan(Tree tree, Void unused) {
                    if (tree == null || found[0] != null) return null;
                    long s = positions.getStartPosition(cu, tree);
                    long e = positions.getEndPosition(cu, tree);
                    if (s > end || (e >= 0 && e < start)) return null;
                    if (s == start && e == end && (kinds == null || kinds.contains(tree.getKind()))) {
                        found[0] = new TreePath(getCurrentPath(), tree);
                        return null;
                    }
                    return super.scan(tree, unused);
                }
            }.scan(cu, null);
            return found[0];
        }

        /** The innermost tree of an accepted kind containing {@code position}. */
        TreePath enclosing(int unit, long position, Set<Tree.Kind> kinds) {
            var cu = trees.get(unit);
            if (cu == null) return null;
            TreePath[] found = new TreePath[1];
            new TreePathScanner<Void, Void>() {
                @Override
                public Void scan(Tree tree, Void unused) {
                    if (tree == null) return null;
                    long s = positions.getStartPosition(cu, tree);
                    long e = positions.getEndPosition(cu, tree);
                    if (s > position || (e >= 0 && e < position)) return null;
                    if (kinds.contains(tree.getKind())) found[0] = new TreePath(getCurrentPath(), tree);
                    return super.scan(tree, unused);
                }
            }.scan(cu, null);
            return found[0];
        }

        TypeMirror type(TreePath parent, Tree child) {
            return javacTrees.getTypeMirror(new TreePath(parent, child));
        }
    }

    /** Rewrites {@code roots} and {@code pool} until no fix applies. */
    public Result rewrite(List<String> roots, List<String> pool) {
        var rootTexts = new ArrayList<>(roots);
        var poolTexts = new ArrayList<>(pool);
        var rules = new TreeSet<String>();
        int round = 0;
        Analysis analysis = null;
        while (round < 40) {
            analysis = new Analysis(rootTexts, poolTexts);
            var edits = new LinkedHashMap<Integer, List<Edit>>();
            var imports = new LinkedHashMap<Integer, Set<String>>();
            for (var d : analysis.errors) {
                Integer unit = analysis.unit(d);
                if (unit == null || analysis.trees.get(unit) == null) continue;
                fix(analysis, unit, d, edits, imports);
            }
            if (edits.isEmpty() && imports.isEmpty()) break;
            round++;
            boolean any = false;
            for (int unit = 0; unit < analysis.texts.size(); unit++) {
                String before = analysis.texts.get(unit);
                var unitEdits = new ArrayList<>(edits.getOrDefault(unit, List.of()));
                Edit importEdit = importEdit(before, analysis.trees.get(unit), analysis.positions,
                        imports.getOrDefault(unit, Set.of()));
                if (importEdit != null) unitEdits.add(importEdit);
                String after = apply(before, unitEdits, rules);
                if (!after.equals(before)) {
                    any = true;
                    if (unit < rootTexts.size()) rootTexts.set(unit, after);
                    else poolTexts.set(unit - rootTexts.size(), after);
                }
            }
            if (!any) break;
        }
        var remaining = new ArrayList<String>();
        for (var d : analysis.errors) {
            Integer unit = analysis.unit(d);
            String name = d.getSource() == null ? "-" : d.getSource().getName();
            remaining.add(d.getCode() + " " + name.substring(name.lastIndexOf('/') + 1) + ":" + d.getLineNumber()
                    + ":" + d.getColumnNumber() + " " + d.getMessage(Locale.ENGLISH).replace('\n', ' ')
                    + (unit == null ? " [not ours]" : ""));
        }
        return new Result(rootTexts, poolTexts, remaining, round, List.copyOf(rules));
    }

    // --- fixes ---

    private static final Set<Tree.Kind> ARITHMETIC = Set.of(Tree.Kind.PLUS, Tree.Kind.MINUS, Tree.Kind.MULTIPLY,
            Tree.Kind.DIVIDE, Tree.Kind.REMAINDER);
    private static final Set<Tree.Kind> RELATIONAL = Set.of(Tree.Kind.LESS_THAN, Tree.Kind.LESS_THAN_EQUAL,
            Tree.Kind.GREATER_THAN, Tree.Kind.GREATER_THAN_EQUAL);
    private static final Set<Tree.Kind> EQUALITY = Set.of(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO);
    private static final Set<Tree.Kind> COMPOUND = Set.of(Tree.Kind.PLUS_ASSIGNMENT, Tree.Kind.MINUS_ASSIGNMENT,
            Tree.Kind.MULTIPLY_ASSIGNMENT, Tree.Kind.DIVIDE_ASSIGNMENT, Tree.Kind.REMAINDER_ASSIGNMENT);
    private static final Set<Tree.Kind> UNARY = Set.of(Tree.Kind.UNARY_MINUS, Tree.Kind.POSTFIX_INCREMENT,
            Tree.Kind.PREFIX_INCREMENT, Tree.Kind.POSTFIX_DECREMENT, Tree.Kind.PREFIX_DECREMENT);
    private static final Set<Tree.Kind> PRIMARY = Set.of(Tree.Kind.IDENTIFIER, Tree.Kind.MEMBER_SELECT,
            Tree.Kind.METHOD_INVOCATION, Tree.Kind.PARENTHESIZED, Tree.Kind.ARRAY_ACCESS, Tree.Kind.NEW_CLASS);

    private void fix(Analysis a, int unit, Diagnostic<? extends JavaFileObject> d, Map<Integer, List<Edit>> edits,
                     Map<Integer, Set<String>> imports) {
        String key = d.getCode();
        String message = d.getMessage(Locale.ENGLISH);
        long start = d.getStartPosition();
        long end = d.getEndPosition();
        long pos = d.getPosition();
        var out = edits.computeIfAbsent(unit, k -> new ArrayList<>());
        switch (key) {
            case "compiler.err.cant.resolve", "compiler.err.cant.resolve.location" -> {
                Matcher m = SYMBOL.matcher(message);
                if (!m.find()) return;
                String name = m.group(2);
                var path = a.find(unit, start, end, Set.of(Tree.Kind.IDENTIFIER));
                // A source-path (pool) unit has no end positions: find the identifier by its start.
                if (path == null) path = a.enclosing(unit, pos, Set.of(Tree.Kind.IDENTIFIER));
                if (path == null || a.start(unit, path.getLeaf()) != pos
                        || !((IdentifierTree) path.getLeaf()).getName().contentEquals(name)) return;
                String fqcn = importFor(a, unit, name);
                if (fqcn != null) {
                    imports.computeIfAbsent(unit, k -> new LinkedHashSet<>()).add(fqcn);
                    return;
                }
                // A sealed variant named in a case label: qualify it by the selector's type.
                String qualified = qualifyCaseLabel(a, unit, path, name);
                if (qualified != null && end > start) out.add(new Edit((int) start, (int) end, qualified, "qualify-case-label"));
            }
            case "compiler.err.cant.resolve.location.args" -> {
                Matcher onUtil = UTIL_VARIABLE.matcher(message);
                if (onUtil.find()) {
                    // A JulcList/JulcMap method on a java.util.List/Map variable: JuLC typed both alike.
                    retargetVariable(a, unit, pos, onUtil.group(1), out, imports);
                    return;
                }
                Matcher m = METHOD_SYMBOL.matcher(message);
                if (!m.find() || !m.group(1).equals("of")) return;
                var path = a.enclosing(unit, pos, Set.of(Tree.Kind.METHOD_INVOCATION));
                if (path == null) return;
                var call = (MethodInvocationTree) path.getLeaf();
                if (!(call.getMethodSelect() instanceof MemberSelectTree select)
                        || !select.getIdentifier().contentEquals("of")) return;
                Element owner = a.javacTrees.getElement(new TreePath(new TreePath(path, select), select.getExpression()));
                if (!(owner instanceof TypeElement type) || !isNewType(type)) return;
                out.add(new Edit((int) a.start(unit, select), (int) a.end(unit, select),
                        "new " + a.text(unit, select.getExpression()), "newtype-of"));
            }
            case "compiler.err.operator.cant.be.applied.1" -> {
                var path = a.find(unit, start, end, null);
                if (path == null) return;
                Tree leaf = path.getLeaf();
                if (leaf instanceof BinaryTree binary) {
                    String text = binary(a, unit, path, binary);
                    String rule = ARITHMETIC.contains(binary.getKind()) ? "biginteger-arithmetic"
                            : RELATIONAL.contains(binary.getKind()) ? "biginteger-compareTo"
                            : binary.getKind() == Tree.Kind.EQUAL_TO ? "biginteger-equals" : "biginteger-not-equals";
                    if (text != null) out.add(new Edit((int) start, (int) end, text, rule));
                } else if (leaf instanceof CompoundAssignmentTree compound) {
                    String text = compound(a, unit, path, compound);
                    if (text != null) out.add(new Edit((int) start, (int) end, text, "biginteger-compound"));
                }
            }
            case "compiler.err.operator.cant.be.applied" -> {
                var path = a.find(unit, start, end, UNARY);
                if (path == null) return;
                var unary = (UnaryTree) path.getLeaf();
                if (!isBig(a.type(path, unary.getExpression()))) return;
                String operand = a.text(unit, unary.getExpression());
                if (unary.getKind() == Tree.Kind.UNARY_MINUS) {
                    out.add(new Edit((int) start, (int) end, receiver(a, unit, unary.getExpression()) + ".negate()",
                            "biginteger-negate"));
                } else if (path.getParentPath().getLeaf() instanceof ExpressionStatementTree) {
                    boolean inc = unary.getKind() == Tree.Kind.POSTFIX_INCREMENT
                            || unary.getKind() == Tree.Kind.PREFIX_INCREMENT;
                    out.add(new Edit((int) start, (int) end, operand + " = " + operand
                            + (inc ? ".add(BigInteger.ONE)" : ".subtract(BigInteger.ONE)"), "biginteger-increment"));
                }
            }
            case "compiler.err.prob.found.req" -> {
                Matcher to = TO_BIG.matcher(message);
                Matcher from = FROM_BIG.matcher(message);
                Matcher data = DATA_CONVERSION.matcher(message);
                if (!to.find() && !from.find()) {
                    Matcher util = TO_UTIL.matcher(message);
                    if (util.find() && end > start) {
                        var p = a.find(unit, start, end, null);
                        if (p != null) {
                            Tree parent = p.getParentPath().getLeaf();
                            if (parent instanceof VariableTree variable && variable.getInitializer() == p.getLeaf()) {
                                retargetType(a, unit, variable.getType(), out, imports);
                            }
                        }
                        return;
                    }
                    if (data.find()) dataCast(a, unit, start, end, data, out, imports);
                    return;
                }
                var path = a.find(unit, start, end, null);
                if (path == null || !(path.getLeaf() instanceof ExpressionTree expr)) return;
                if (to.find(0)) {
                    out.add(new Edit((int) start, (int) end, big(a, unit, expr, true), "int-to-biginteger"));
                } else {
                    String method = from.group(1).equals("int") ? ".intValue()" : ".longValue()";
                    out.add(new Edit((int) start, (int) end, receiver(a, unit, expr) + method, "biginteger-to-" + from.group(1)));
                }
            }
            case "compiler.err.doesnt.exist" -> {
                // A wildcard import of a JuLC package that does not exist: JuLC ignored it.
                var path = a.enclosing(unit, pos, Set.of(Tree.Kind.IMPORT));
                if (path == null) return;
                var imp = (ImportTree) path.getLeaf();
                String name = imp.getQualifiedIdentifier().toString();
                if (imp.isStatic() || !name.startsWith("org.julclang.") || !name.endsWith(".*")) return;
                String text = a.texts.get(unit);
                int s = text.lastIndexOf("import", (int) a.start(unit, imp));
                if (s < 0) return;
                int e = text.indexOf(';', s) + 1;
                if (e < s + 1) return;
                if (e < text.length() && text.charAt(e) == '\n') e++;
                out.add(new Edit(s, e, "", "drop-missing-import"));
            }
            case "compiler.err.non-static.cant.be.ref" -> {
                Matcher m = NON_STATIC.matcher(message);
                if (!m.find()) return;
                boolean method = m.group(1).equals("method");
                String name = m.group(2);
                var classPath = a.enclosing(unit, pos, Set.of(Tree.Kind.CLASS, Tree.Kind.INTERFACE, Tree.Kind.RECORD,
                        Tree.Kind.ENUM));
                for (var p = classPath; p != null; p = p.getParentPath()) {
                    if (!(p.getLeaf() instanceof ClassTree type)) continue;
                    for (Tree member : type.getMembers()) {
                        if (!method && member instanceof VariableTree field && field.getName().contentEquals(name)
                                && !field.getModifiers().getFlags().contains(Modifier.STATIC)) {
                            long at = a.start(unit, field.getType());
                            out.add(new Edit((int) at, (int) at, "static ", "static-field"));
                            return;
                        }
                        if (method && member instanceof MethodTree decl && decl.getName().contentEquals(name)
                                && !decl.getModifiers().getFlags().contains(Modifier.STATIC)
                                && decl.getReturnType() != null) {
                            long at = decl.getTypeParameters().isEmpty() ? a.start(unit, decl.getReturnType())
                                    : a.start(unit, decl.getTypeParameters().getFirst()) - 1;
                            out.add(new Edit((int) at, (int) at, "static ", "static-method"));
                            return;
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    /**
     * JuLC's Data typing: a {@code PlutusData} where a typed value (record, sealed interface, ledger type,
     * {@code JulcList}/{@code JulcMap}) is declared, or a record or sealed value where {@code PlutusData} is, becomes
     * {@code PlutusData.cast(value, T.class)}, which JuLC lowers to the value itself (as it lowered the dialect).
     * Only for a variable initializer or a {@code return}, whose declared type names {@code T} in the source.
     */
    private void dataCast(Analysis a, int unit, long start, long end, Matcher data, List<Edit> out,
                          Map<Integer, Set<String>> imports) {
        if (end <= start) return;
        var path = a.find(unit, start, end, null);
        if (path == null || !(path.getLeaf() instanceof ExpressionTree expr)) return;
        boolean toData = data.group(2).equals("org.julclang.core.PlutusData");
        boolean fromData = data.group(1).equals("org.julclang.core.PlutusData")
                || data.group(1).startsWith("org.julclang.core.PlutusData.");
        if (toData == fromData) return;
        if (toData) {
            TypeMirror type = a.javacTrees.getTypeMirror(path);
            if (!(type instanceof DeclaredType declared)) return;
            var kind = declared.asElement().getKind();
            if (kind != ElementKind.RECORD && kind != ElementKind.INTERFACE) return;
        }
        Tree parent = path.getParentPath().getLeaf();
        Tree typeTree = null;
        if (parent instanceof VariableTree variable && variable.getInitializer() == expr) {
            typeTree = variable.getType();
        } else if (parent instanceof com.sun.source.tree.ReturnTree) {
            for (var p = path.getParentPath(); p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof com.sun.source.tree.LambdaExpressionTree) return;
                if (p.getLeaf() instanceof MethodTree method) {
                    typeTree = method.getReturnType();
                    break;
                }
            }
        }
        if (typeTree == null || a.start(unit, typeTree) < 0 || a.end(unit, typeTree) < 0) return;
        String typeText = a.text(unit, typeTree);
        if (typeText.equals("var")) return;
        String erased = typeText.replaceAll("<.*>", "").strip();
        String value = a.text(unit, expr);
        out.add(new Edit((int) start, (int) end, "PlutusData.cast(" + value + ", " + erased + ".class)", "data-cast"));
        if (!a.texts.get(unit).contains("import org.julclang.core.PlutusData;")) {
            String fqcn = importFor(a, unit, "PlutusData");
            if (fqcn != null) imports.computeIfAbsent(unit, k -> new LinkedHashSet<>()).add(fqcn);
        }
    }

    /** The declaration of local or parameter {@code name} visible at {@code pos}, retyped to JulcList/JulcMap. */
    private void retargetVariable(Analysis a, int unit, long pos, String name, List<Edit> out,
                                  Map<Integer, Set<String>> imports) {
        var method = a.enclosing(unit, pos, Set.of(Tree.Kind.METHOD, Tree.Kind.LAMBDA_EXPRESSION));
        for (var p = method; p != null; p = p.getParentPath()) {
            Tree found = null;
            if (p.getLeaf() instanceof MethodTree m) {
                for (var param : m.getParameters()) if (param.getName().contentEquals(name)) found = param;
                if (found == null) {
                    VariableTree[] local = new VariableTree[1];
                    new TreePathScanner<Void, Void>() {
                        @Override
                        public Void visitVariable(VariableTree node, Void unused) {
                            if (node.getName().contentEquals(name) && local[0] == null) local[0] = node;
                            return super.visitVariable(node, unused);
                        }
                    }.scan(new TreePath(p, m.getBody()), null);
                    found = local[0];
                }
            }
            if (found instanceof VariableTree variable) {
                retargetType(a, unit, variable.getType(), out, imports);
                return;
            }
        }
    }

    /** {@code List<..>}/{@code Map<..>} written in the source becomes {@code JulcList<..>}/{@code JulcMap<..>}. */
    private void retargetType(Analysis a, int unit, Tree type, List<Edit> out, Map<Integer, Set<String>> imports) {
        if (!(type instanceof com.sun.source.tree.ParameterizedTypeTree parameterized)) return;
        Tree base = parameterized.getType();
        if (!(base instanceof IdentifierTree id) || a.start(unit, base) < 0) return;
        String replacement = id.getName().contentEquals("List") ? "JulcList"
                : id.getName().contentEquals("Map") ? "JulcMap" : null;
        if (replacement == null) return;
        long s = a.start(unit, base);
        out.add(new Edit((int) s, (int) s + id.getName().length(), replacement, "util-to-julc-collection"));
        String fqcn = "org.julclang.core.types." + replacement;
        if (!a.texts.get(unit).contains("import " + fqcn + ";")) {
            imports.computeIfAbsent(unit, k -> new LinkedHashSet<>()).add(fqcn);
        }
    }

    private String binary(Analysis a, int unit, TreePath path, BinaryTree binary) {
        TypeMirror left = a.type(path, binary.getLeftOperand());
        TypeMirror right = a.type(path, binary.getRightOperand());
        if (!(isBig(left) || isBig(right)) || !(isBig(left) || isIntegral(left)) || !(isBig(right) || isIntegral(right)))
            return null;
        String receiver = isBig(left) ? receiver(a, unit, binary.getLeftOperand()) : big(a, unit, binary.getLeftOperand(), false);
        String argument = isBig(right) ? argument(a, unit, binary.getRightOperand()) : big(a, unit, binary.getRightOperand(), true);
        Tree.Kind kind = binary.getKind();
        if (ARITHMETIC.contains(kind)) return receiver + "." + arithmetic(kind) + "(" + argument + ")";
        if (RELATIONAL.contains(kind)) return receiver + ".compareTo(" + argument + ") " + operator(kind) + " 0";
        if (EQUALITY.contains(kind))
            return (kind == Tree.Kind.NOT_EQUAL_TO ? "!" : "") + receiver + ".equals(" + argument + ")";
        return null;
    }

    private String compound(Analysis a, int unit, TreePath path, CompoundAssignmentTree compound) {
        TypeMirror target = a.type(path, compound.getVariable());
        TypeMirror value = a.type(path, compound.getExpression());
        if (!isBig(target) || !(isBig(value) || isIntegral(value))) return null;
        if (!(compound.getVariable() instanceof IdentifierTree)) return null;
        String variable = a.text(unit, compound.getVariable());
        String argument = isBig(value) ? argument(a, unit, compound.getExpression())
                : big(a, unit, compound.getExpression(), true);
        String method = switch (compound.getKind()) {
            case PLUS_ASSIGNMENT -> "add";
            case MINUS_ASSIGNMENT -> "subtract";
            case MULTIPLY_ASSIGNMENT -> "multiply";
            case DIVIDE_ASSIGNMENT -> "divide";
            case REMAINDER_ASSIGNMENT -> "remainder";
            default -> null;
        };
        return method == null ? null : variable + " = " + variable + "." + method + "(" + argument + ")";
    }

    private static String arithmetic(Tree.Kind kind) {
        return switch (kind) {
            case PLUS -> "add";
            case MINUS -> "subtract";
            case MULTIPLY -> "multiply";
            case DIVIDE -> "divide";
            case REMAINDER -> "remainder";
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    private static String operator(Tree.Kind kind) {
        return switch (kind) {
            case LESS_THAN -> "<";
            case LESS_THAN_EQUAL -> "<=";
            case GREATER_THAN -> ">";
            case GREATER_THAN_EQUAL -> ">=";
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    /** {@code tree}'s text as a method receiver, parenthesized unless it is a primary. */
    private static String receiver(Analysis a, int unit, ExpressionTree tree) {
        if (tree instanceof ParenthesizedTree paren && PRIMARY.contains(paren.getExpression().getKind())) {
            return a.text(unit, paren.getExpression());
        }
        String text = a.text(unit, tree);
        return PRIMARY.contains(tree.getKind()) ? text : "(" + text + ")";
    }

    /** {@code tree}'s text as a method argument, without redundant parentheses. */
    private static String argument(Analysis a, int unit, ExpressionTree tree) {
        return tree instanceof ParenthesizedTree paren ? a.text(unit, paren.getExpression()) : a.text(unit, tree);
    }

    /** An integral expression as a {@code BigInteger}: a constant for 0, 1, 2 and 10, else {@code valueOf}. */
    private static String big(Analysis a, int unit, ExpressionTree tree, boolean asArgument) {
        ExpressionTree inner = tree;
        while (inner instanceof ParenthesizedTree paren) inner = paren.getExpression();
        if (inner instanceof LiteralTree literal && literal.getValue() instanceof Number number) {
            switch (number.intValue()) {
                case 0 -> { if (number.longValue() == 0) return "BigInteger.ZERO"; }
                case 1 -> { if (number.longValue() == 1) return "BigInteger.ONE"; }
                case 2 -> { if (number.longValue() == 2) return "BigInteger.TWO"; }
                case 10 -> { if (number.longValue() == 10) return "BigInteger.TEN"; }
                default -> { }
            }
        }
        return "BigInteger.valueOf(" + a.text(unit, inner) + ")";
    }

    private static boolean isBig(TypeMirror type) {
        return type != null && type.getKind() == TypeKind.DECLARED
                && ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().contentEquals("java.math.BigInteger");
    }

    private static boolean isIntegral(TypeMirror type) {
        return type != null && switch (type.getKind()) {
            case INT, LONG, SHORT, BYTE -> true;
            default -> false;
        };
    }

    private static boolean isNewType(TypeElement type) {
        return type.getAnnotationMirrors().stream().anyMatch(m -> ((TypeElement) m.getAnnotationType().asElement())
                .getQualifiedName().contentEquals("org.julclang.stdlib.annotation.NewType"));
    }

    /** The import for a simple name: a type another unit declares, else JuLC's API, else null. */
    private String importFor(Analysis a, int unit, String name) {
        var cu = a.trees.get(unit);
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        for (ImportTree imp : cu.getImports()) {
            String imported = imp.getQualifiedIdentifier().toString();
            if (!imp.isStatic() && imported.endsWith("." + name)) return null;
        }
        String own = a.declared.get(name);
        if (own != null) {
            int dot = own.lastIndexOf('.');
            String ownPkg = dot < 0 ? "" : own.substring(0, dot);
            return ownPkg.isEmpty() || ownPkg.equals(pkg) ? null : own;
        }
        return apiImports.get(name);
    }

    /** {@code Outer.Name} when {@code ident} is a case label's type and the selector's type has a member {@code Name}. */
    private static String qualifyCaseLabel(Analysis a, int unit, TreePath ident, String name) {
        for (var p = ident.getParentPath(); p != null; p = p.getParentPath()) {
            Tree leaf = p.getLeaf();
            ExpressionTree selector = null;
            if (leaf instanceof com.sun.source.tree.SwitchTree s) selector = s.getExpression();
            else if (leaf instanceof com.sun.source.tree.SwitchExpressionTree s) selector = s.getExpression();
            else if (leaf instanceof MethodTree || leaf instanceof ClassTree) return null;
            if (selector == null) continue;
            TypeMirror type = a.type(p, selector);
            if (!(type instanceof DeclaredType declared)) return null;
            var element = (TypeElement) declared.asElement();
            for (Element member : element.getEnclosedElements()) {
                if ((member.getKind() == ElementKind.RECORD || member.getKind() == ElementKind.CLASS
                        || member.getKind() == ElementKind.INTERFACE) && member.getSimpleName().contentEquals(name)) {
                    return element.getSimpleName() + "." + name;
                }
            }
            return null;
        }
        return null;
    }

    /**
     * JuLC's implicit imports, as javac sees the API: every stub class by simple name, top-level types first, plus
     * the JDK types the dialect used unimported. A simple name two stub types share is left out.
     */
    static Map<String, String> apiImports(JavacPlatform platform) {
        var topLevel = new TreeMap<String, Set<String>>();
        var nested = new TreeMap<String, Set<String>>();
        platform.classPath().forEach((pkg, files) -> files.keySet().forEach(simple -> {
            if (simple.equals("package-info") || simple.equals("module-info")) return;
            String fqcn = (pkg.isEmpty() ? "" : pkg + ".") + simple.replace('$', '.');
            String last = simple.substring(simple.lastIndexOf('$') + 1);
            if (last.isEmpty() || Character.isDigit(last.charAt(0))) return;
            (simple.indexOf('$') < 0 ? topLevel : nested).computeIfAbsent(last, k -> new TreeSet<>()).add(fqcn);
        }));
        var table = new TreeMap<String, String>();
        nested.forEach((name, fqcns) -> { if (fqcns.size() == 1) table.put(name, fqcns.iterator().next()); });
        topLevel.forEach((name, fqcns) -> {
            if (fqcns.size() == 1) table.put(name, fqcns.iterator().next());
            else table.remove(name);
        });
        for (String jdk : List.of("java.math.BigInteger", "java.util.List", "java.util.Map", "java.util.Optional")) {
            table.put(jdk.substring(jdk.lastIndexOf('.') + 1), jdk);
        }
        return table;
    }

    // --- applying edits ---

    /** Applies the innermost non-overlapping edits; an edit enclosing another waits for the next round. */
    static String apply(String text, List<Edit> edits, Set<String> rules) {
        if (edits.isEmpty()) return text;
        var unique = new ArrayList<Edit>(new LinkedHashSet<>(edits));
        var kept = new ArrayList<Edit>();
        for (Edit e : unique) {
            boolean enclosesAnother = false;
            for (Edit o : unique) {
                if (o == e) continue;
                boolean overlaps = o.start() < e.end() && e.start() < o.end()
                        || (o.start() == o.end() && o.start() > e.start() && o.start() < e.end());
                if (overlaps && (e.end() - e.start() > o.end() - o.start()
                        || (e.end() - e.start() == o.end() - o.start() && unique.indexOf(o) < unique.indexOf(e)))) {
                    enclosesAnother = true;
                    break;
                }
            }
            if (!enclosesAnother) kept.add(e);
        }
        kept.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end).reversed());
        var out = new StringBuilder(text);
        int limit = Integer.MAX_VALUE;
        for (Edit e : kept) {
            if (e.end() > limit) continue;
            out.replace(e.start(), e.end(), e.text());
            rules.add(e.rule());
            limit = e.start();
        }
        return out.toString();
    }

    /** Inserts single-type imports after the last import, else after the package, else first; one per line. */
    static Edit importEdit(String text, CompilationUnitTree cu, SourcePositions positions, Set<String> imports) {
        if (imports.isEmpty() || cu == null) return null;
        var block = new StringBuilder();
        for (String fqcn : new TreeSet<>(imports)) block.append("import ").append(fqcn).append(";\n");
        // Start positions only: a source-path (pool) unit has no end positions.
        long after = -1;
        for (ImportTree imp : cu.getImports()) after = Math.max(after, positions.getStartPosition(cu, imp));
        if (after < 0 && cu.getPackage() != null) after = positions.getStartPosition(cu, cu.getPackage());
        if (after < 0) {
            int first = 0;
            while (first < text.length() && Character.isWhitespace(text.charAt(first)) && text.charAt(first) != '\n') {
                first++;
            }
            return new Edit(first, first, block.toString(), "import");
        }
        int lineEnd = text.indexOf('\n', (int) after);
        if (lineEnd < 0) return new Edit(text.length(), text.length(), "\n" + block.toString().stripTrailing(), "import");
        return new Edit(lineEnd + 1, lineEnd + 1, block.toString(), "import");
    }

    // --- the driver over a gate report ---

    /** A string literal of a repository file, or a whole on-disk Java file. */
    record Site(Path file, int start, int end, boolean wholeFile, boolean textBlock) {
    }

    /**
     * {@code main(reportDir, repoRoot, outDir [, apply])}: runs the codemod on every rejected case of the report,
     * writes {@code outDir/cases.tsv} (one line per case: digest, origins, status, rules, diagnostics left) and, with
     * {@code apply}, writes every rewrite back into its source.
     */
    public static void main(String[] args) throws IOException {
        if (args[0].equals("markdown")) {
            markdown(Path.of(args[1]), List.of(args).subList(2, args.length));
            return;
        }
        if (args[0].equals("compare")) {
            // compare <old.java> <new.java> [method]: FLAT bytes of both, gate off; then the gate's verdict on new.
            String oldText = Files.readString(Path.of(args[1]));
            String newText = Files.readString(Path.of(args[2]));
            var entries = args.length > 3 ? Set.of("compileMethod") : Set.<String>of();
            if (args.length > 3) {
                var options = new org.julclang.compiler.CompilerOptions().setJavacGate(JavacGateMode.OFF);
                var compiler = new org.julclang.compiler.JulcCompiler(org.julclang.stdlib.StdlibRegistry.defaultRegistry(), options);
                System.out.println("verdict: " + compare(() -> compiler.compileMethod(oldText, args[3]),
                        () -> compiler.compileMethod(newText, args[3]), () -> null, () -> null));
            } else {
                System.out.println("verdict: " + verify(oldText, List.of(), newText, List.of(), entries));
            }
            var result = JavacFrontend.shared().analyze(List.of(JavacFrontend.Unit.of(newText)), List.of());
            result.diagnostics().forEach(d -> System.out.println("javac: " + d.line() + ":" + d.column() + " " + d.message()));
            return;
        }
        if (args[0].equals("debug")) {
            debug(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        Path report = Path.of(args[0]);
        Path repo = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        boolean apply = args.length > 3 && args[3].equals("apply");
        Files.createDirectories(outDir);
        var sites = index(repo);
        System.out.println("indexed " + sites.size() + " distinct texts");
        var codemod = new DialectCodemod();
        var rewrites = new TreeMap<String, String>();     // digest -> best rewrite
        var leftFor = new HashMap<String, Integer>();      // digest -> diagnostics left in the best rewrite's case
        var tsv = new StringBuilder("case\torigins\tstatus\tverified\trounds\trules\troots\tremaining\n");
        List<Path> cases;
        try (Stream<Path> list = Files.list(report.resolve("cases"))) {
            cases = list.sorted().toList();
        }
        int n = 0;
        for (Path dir : cases) {
            if (++n % 250 == 0) System.out.println(n + "/" + cases.size());
            var roots = new ArrayList<String>();
            var pool = new ArrayList<String>();
            var rootDigests = new ArrayList<String>();
            var poolDigests = new ArrayList<String>();
            for (String line : Files.readAllLines(dir.resolve("case.txt"))) {
                if (line.startsWith("root: ") || line.startsWith("pool: ")) {
                    String digest = line.substring(6).split(" ", 2)[0];
                    String text = Files.readString(report.resolve("sources").resolve(digest + ".java"));
                    (line.startsWith("root") ? roots : pool).add(text);
                    (line.startsWith("root") ? rootDigests : poolDigests).add(digest);
                }
            }
            var origins = new TreeSet<String>();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    if (f.getFileName().toString().startsWith("origin-")) origins.add(Files.readString(f).strip());
                }
            }
            String status;
            Result result = null;
            boolean located = rootDigests.stream().anyMatch(sites::containsKey);
            try {
                result = codemod.rewrite(roots, pool);
            } catch (RuntimeException e) {
                status = "error " + e;
            }
            if (result == null) {
                status = "error";
            } else if (!result.changed(roots, pool)) {
                status = "unchanged";
            } else {
                status = result.remaining().isEmpty() ? "clean" : "partial";
                for (int i = 0; i < roots.size() + pool.size(); i++) {
                    boolean root = i < roots.size();
                    String digest = root ? rootDigests.get(i) : poolDigests.get(i - roots.size());
                    String before = root ? roots.get(i) : pool.get(i - roots.size());
                    String after = root ? result.roots().get(i) : result.pool().get(i - roots.size());
                    if (after.equals(before)) continue;
                    int left = result.remaining().size();
                    if (!rewrites.containsKey(digest) || left < leftFor.get(digest)) {
                        rewrites.put(digest, after);
                        leftFor.put(digest, left);
                    }
                }
            }
            if (!located) status += " unlocated";
            String verified = "";
            if (result != null && result.changed(roots, pool) && result.remaining().isEmpty() && !roots.isEmpty()) {
                var entries = new TreeSet<String>();
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : files.toList()) {
                        String name = f.getFileName().toString();
                        if (name.startsWith("entry-")) entries.add(name.substring(6));
                    }
                }
                verified = verify(roots.getFirst(), pool, result.roots().getFirst(), result.pool(), entries);
            }
            status += "\t" + verified;
            tsv.append(dir.getFileName()).append('\t').append(String.join(",", origins)).append('\t').append(status)
                    .append('\t').append(result == null ? 0 : result.rounds()).append('\t')
                    .append(result == null ? "" : String.join(",", result.rules())).append('\t')
                    .append(String.join(",", rootDigests.stream().map(d -> d.substring(0, 12)
                            + (sites.containsKey(d) ? "@" + sites.get(d).size() : "")).toList())).append('\t')
                    .append(result == null ? "" : String.join(" || ", result.remaining())).append('\n');
        }
        Files.writeString(outDir.resolve("cases.tsv"), tsv.toString());
        // Write back.
        var byFile = new TreeMap<Path, List<Map.Entry<Site, String>>>();
        var unlocated = new StringBuilder();
        for (var e : rewrites.entrySet()) {
            var at = sites.get(e.getKey());
            if (at == null) {
                unlocated.append(e.getKey()).append('\n');
                continue;
            }
            for (Site site : at) byFile.computeIfAbsent(site.file(), k -> new ArrayList<>()).add(Map.entry(site, e.getValue()));
        }
        Files.writeString(outDir.resolve("unlocated-rewrites.txt"), unlocated.toString());
        var written = new StringBuilder();
        for (var e : byFile.entrySet()) {
            String original = Files.readString(e.getKey());
            String updated = original;
            var list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingInt((Map.Entry<Site, String> s) -> s.getKey().start()).reversed());
            int failures = 0;
            for (var s : list) {
                Site site = s.getKey();
                if (site.wholeFile()) {
                    updated = s.getValue();
                    continue;
                }
                String raw = updated.substring(site.start(), site.end());
                String encoded = site.textBlock() ? reencodeTextBlock(raw, s.getValue()) : reencodeString(raw, s.getValue());
                if (encoded == null) {
                    failures++;
                    continue;
                }
                updated = updated.substring(0, site.start()) + encoded + updated.substring(site.end());
            }
            written.append(e.getKey()).append('\t').append(list.size()).append('\t').append(failures).append('\n');
            if (apply && !updated.equals(original)) Files.writeString(e.getKey(), updated);
        }
        Files.writeString(outDir.resolve("written.tsv"), written.toString());
        System.out.println("rewrites: " + rewrites.size() + ", files: " + byFile.size() + ", unlocated: "
                + unlocated.toString().lines().count());
    }

    private static final Pattern STATIC_METHOD = Pattern.compile("static\\s+[\\w<>\\[\\], .?]+?\\s+(\\w+)\\s*\\(");

    /**
     * Compiles the original and the rewritten root with the JavaParser frontend (gate off) and compares the FLAT
     * bytes: {@code same}, {@code differ:<old>-><new>} (sizes), or why it could not tell. A method entry tries each
     * static method the root declares.
     */
    static String verify(String oldRoot, List<String> oldPool, String newRoot, List<String> newPool, Set<String> entries) {
        var options = new org.julclang.compiler.CompilerOptions().setJavacGate(JavacGateMode.OFF);
        var compiler = new org.julclang.compiler.JulcCompiler(org.julclang.stdlib.StdlibRegistry.defaultRegistry(), options);
        var outcomes = new TreeSet<String>();
        if (entries.contains("compileMethod")) {
            var names = new LinkedHashSet<String>();
            Matcher m = STATIC_METHOD.matcher(oldRoot);
            while (m.find()) names.add(m.group(1));
            for (String name : names) {
                outcomes.add(compare(() -> compiler.compileMethod(oldRoot, name),
                        () -> compiler.compileMethod(newRoot, name), () -> compiler.compileMethod(oldRoot, name, oldPool),
                        () -> compiler.compileMethod(newRoot, name, newPool)));
            }
        } else {
            outcomes.add(compare(() -> compiler.compileWithDetails(oldRoot), () -> compiler.compileWithDetails(newRoot),
                    () -> compiler.compileWithDetails(oldRoot, oldPool), () -> compiler.compileWithDetails(newRoot, newPool)));
        }
        outcomes.remove("n/a");
        return outcomes.isEmpty() ? "n/a" : String.join(";", outcomes);
    }

    private static String compare(java.util.function.Supplier<org.julclang.compiler.CompileResult> oldAlone,
                                  java.util.function.Supplier<org.julclang.compiler.CompileResult> newAlone,
                                  java.util.function.Supplier<org.julclang.compiler.CompileResult> oldWithPool,
                                  java.util.function.Supplier<org.julclang.compiler.CompileResult> newWithPool) {
        byte[] before = flat(oldAlone);
        byte[] after;
        if (before != null) {
            after = flat(newAlone);
        } else {
            before = flat(oldWithPool);
            if (before == null) return "n/a";
            after = flat(newWithPool);
        }
        if (after == null) return "new-fails";
        return java.util.Arrays.equals(before, after) ? "same" : "differ:" + before.length + "->" + after.length;
    }

    private static byte[] flat(java.util.function.Supplier<org.julclang.compiler.CompileResult> compile) {
        try {
            var result = compile.get();
            if (result == null || result.hasErrors() || result.program() == null) return null;
            return org.julclang.core.flat.UplcFlatEncoder.encodeProgram(result.program());
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    private static final Pattern FENCE = Pattern.compile("(?m)^([ \\t]*)```java[ \\t]*\\n(.*?)^\\1```", Pattern.DOTALL);
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)^(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+)*(?:class|record|interface|enum)\\s+\\w+");

    /**
     * Rewrites the {@code java} fenced blocks of Markdown (or MDX) files that hold whole compilation units (a
     * top-level type declaration), each analyzed alone; writes {@code outDir/markdown.tsv}: file, block line, status,
     * rules, diagnostics left.
     */
    static void markdown(Path outDir, List<String> files) throws IOException {
        var codemod = new DialectCodemod();
        var tsv = new StringBuilder("file\tline\tstatus\trules\tremaining\n");
        for (String name : files) {
            Path file = Path.of(name);
            String text = Files.readString(file);
            Matcher m = FENCE.matcher(text);
            var out = new StringBuilder();
            int last = 0;
            while (m.find()) {
                String indent = m.group(1);
                String body = m.group(2);
                int line = (int) text.substring(0, m.start()).lines().count() + 1;
                String code = body.lines().map(l -> l.startsWith(indent) ? l.substring(indent.length()) : l)
                        .reduce("", (x, y) -> x + y + "\n");
                if (!DECLARATION.matcher(code).find()) {
                    tsv.append(file).append('\t').append(line).append("\tfragment\t\t\n");
                    continue;
                }
                Result r = codemod.rewrite(List.of(code), List.of());
                String rewritten = r.roots().getFirst();
                String status = rewritten.equals(code) ? (r.remaining().isEmpty() ? "valid" : "unchanged")
                        : r.remaining().isEmpty() ? "clean" : "partial";
                tsv.append(file).append('\t').append(line).append('\t').append(status).append('\t')
                        .append(String.join(",", r.rules())).append('\t')
                        .append(String.join(" || ", r.remaining())).append('\n');
                if (!rewritten.equals(code)) {
                    String reindented = rewritten.lines().map(l -> l.isEmpty() ? l : indent + l)
                            .reduce("", (x, y) -> x + y + "\n");
                    out.append(text, last, m.start(2)).append(reindented);
                    last = m.end(2);
                }
            }
            out.append(text.substring(last));
            if (!out.toString().equals(text)) Files.writeString(file, out.toString());
        }
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("markdown.tsv"), tsv.toString());
    }

    /** Prints each round's diagnostics, with extents, and the final texts of one case. */
    static void debug(Path report, Path caseDir) throws IOException {
        var roots = new ArrayList<String>();
        var pool = new ArrayList<String>();
        for (String line : Files.readAllLines(caseDir.resolve("case.txt"))) {
            if (line.startsWith("root: ") || line.startsWith("pool: ")) {
                String digest = line.substring(6).split(" ", 2)[0];
                (line.startsWith("root") ? roots : pool).add(Files.readString(report.resolve("sources").resolve(digest + ".java")));
            }
        }
        var codemod = new DialectCodemod();
        var a = codemod.new Analysis(codemod.rewrite(roots, pool).roots(), pool);
        for (var d : a.errors) {
            Integer unit = a.unit(d);
            String at = unit == null || d.getStartPosition() < 0 ? "?" : a.texts.get(unit).substring((int) d.getStartPosition(),
                    (int) Math.max(d.getStartPosition(), d.getEndPosition()));
            System.out.println(d.getCode() + " " + d.getStartPosition() + "-" + d.getEndPosition() + "@" + d.getPosition()
                    + " [" + at + "] " + d.getMessage(Locale.ENGLISH).replace('\n', ' '));
        }
        for (String text : a.texts) System.out.println("-----\n" + text);
    }

    /** Every string literal of the repository's Java files and every whole Java file, by the digest of its text. */
    static Map<String, List<Site>> index(Path repo) throws IOException {
        var sites = new HashMap<String, List<Site>>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(repo)) {
            files = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/.gradle/")
                            && !p.toString().contains("/node_modules/"))
                    .sorted().toList();
        }
        var javacStd = ToolProvider.getSystemJavaCompiler();
        for (Path file : files) {
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException e) {
                continue;
            }
            sites.computeIfAbsent(CorpusRecorder.sha256(text), k -> new ArrayList<>())
                    .add(new Site(file, 0, text.length(), true, false));
            if (!text.contains("\"")) continue;
            var source = new SimpleJavaFileObject(URI.create("string:///" + file.getFileName()), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return text;
                }
            };
            var task = (JavacTask) javacStd.getTask(null, null, d -> { }, List.of("-proc:none"), null, List.of(source));
            var positions = Trees.instance(task).getSourcePositions();
            try {
                for (CompilationUnitTree cu : task.parse()) {
                    new TreePathScanner<Void, Void>() {
                        @Override
                        public Void visitLiteral(LiteralTree node, Void unused) {
                            if (node.getValue() instanceof String value && value.length() > 10) {
                                int s = (int) positions.getStartPosition(cu, node);
                                int e = (int) positions.getEndPosition(cu, node);
                                boolean block = text.startsWith("\"\"\"", s);
                                sites.computeIfAbsent(CorpusRecorder.sha256(value), k -> new ArrayList<>())
                                        .add(new Site(file, s, e, false, block));
                            }
                            return null;
                        }
                    }.scan(cu, null);
                }
            } catch (IOException | RuntimeException e) {
                // Not parseable as Java: whole-file only.
            }
        }
        return sites;
    }

    // --- re-encoding literals ---

    /**
     * Re-encodes a text block for its new value, keeping every unchanged line's raw text. Null when the raw lines do
     * not map one to one onto the old value's lines (escapes that join or split lines).
     */
    static String reencodeTextBlock(String raw, String value) {
        int open = raw.indexOf('\n');
        int close = raw.lastIndexOf("\"\"\"");
        if (open < 0 || close <= open) return null;
        String body = raw.substring(open + 1, close);
        var rawLines = new ArrayList<>(List.of(body.split("\n", -1)));
        // The last raw line holds the closing delimiter's indentation (or content before it).
        String closing = rawLines.removeLast();
        boolean closingOnOwnLine = closing.isBlank();
        var decoded = decodeTextBlock(raw);
        if (decoded == null) return null;
        String oldValue = decoded.value();
        var oldLines = List.of(oldValue.split("\n", -1));
        var contentRaw = new ArrayList<>(rawLines);
        if (!closingOnOwnLine) contentRaw.add(closing);
        // Old value lines are the raw lines minus the incidental indentation, plus "" when the value ends in \n.
        int indent = decoded.indent();
        var mapped = new ArrayList<String>();
        for (String line : contentRaw) mapped.add(stripIndent(line, indent));
        if (closingOnOwnLine) mapped.add("");
        if (!mapped.equals(oldLines)) return null;
        var newLines = List.of(value.split("\n", -1));
        boolean endsWithNewline = value.endsWith("\n");
        if (closingOnOwnLine != endsWithNewline) return null;
        String prefix = null;
        for (String line : contentRaw) {
            if (!line.isBlank() && line.length() >= indent) {
                prefix = line.substring(0, indent);
                break;
            }
        }
        if (prefix == null) return null;
        // LCS over lines: unchanged lines keep their raw text.
        int n = oldLines.size();
        int m = newLines.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                lcs[i][j] = oldLines.get(i).equals(newLines.get(j)) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        var out = new ArrayList<String>();
        int i = 0;
        int j = 0;
        while (j < m) {
            if (i < n && oldLines.get(i).equals(newLines.get(j))) {
                out.add(i < contentRaw.size() ? contentRaw.get(i) : null);
                i++;
                j++;
            } else if (i < n && lcs[i + 1][j] >= lcs[i][j + 1]) {
                i++;
            } else {
                String line = newLines.get(j);
                out.add(j == m - 1 && endsWithNewline ? null
                        : line.isEmpty() ? "" : prefix + line.replace("\\", "\\\\").replace("\"\"\"", "\\\"\"\""));
                j++;
            }
        }
        // The final "" of a value ending in \n is the closing line.
        if (endsWithNewline) out.removeLast();
        var sb = new StringBuilder(raw.substring(0, open + 1));
        for (String line : out) {
            if (line == null) return null;
            sb.append(line).append('\n');
        }
        if (closingOnOwnLine) sb.append(closing).append("\"\"\"");
        else {
            // The closing delimiter followed the last content line.
            sb.setLength(sb.length() - 1);
            sb.append("\"\"\"");
        }
        sb.append(raw.substring(close + 3));
        String result = sb.toString();
        var check = decodeTextBlock(result);
        return check != null && check.value().equals(value) ? result : null;
    }

    private static String stripIndent(String line, int indent) {
        String stripped = line.length() >= indent ? line.substring(indent) : line.strip();
        return stripTrailing(stripped);
    }

    private static String stripTrailing(String s) {
        int e = s.length();
        while (e > 0 && (s.charAt(e - 1) == ' ' || s.charAt(e - 1) == '\t')) e--;
        return s.substring(0, e);
    }

    record Decoded(String value, int indent) {
    }

    /** A text block's value, by javac (the exact rules), and its incidental indentation; null if not decodable. */
    static Decoded decodeTextBlock(String raw) {
        String value = javaValue(raw);
        if (value == null) return null;
        int open = raw.indexOf('\n');
        int close = raw.lastIndexOf("\"\"\"");
        var lines = List.of(raw.substring(open + 1, close).split("\n", -1));
        int indent = Integer.MAX_VALUE;
        for (int k = 0; k < lines.size(); k++) {
            String line = lines.get(k);
            if (line.isBlank() && k < lines.size() - 1) continue;
            int w = 0;
            while (w < line.length() && (line.charAt(w) == ' ' || line.charAt(w) == '\t')) w++;
            indent = Math.min(indent, w);
        }
        return new Decoded(value, indent == Integer.MAX_VALUE ? 0 : indent);
    }

    /** A string literal's value by javac's parser. */
    static String javaValue(String literal) {
        String text = "class X { String s = " + literal + "; }";
        var source = new SimpleJavaFileObject(URI.create("string:///X.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null, d -> { }, List.of("-proc:none"),
                null, List.of(source));
        try {
            String[] value = new String[1];
            for (CompilationUnitTree cu : task.parse()) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitLiteral(LiteralTree node, Void unused) {
                        if (node.getValue() instanceof String s) value[0] = s;
                        return null;
                    }
                }.scan(cu, null);
            }
            return value[0];
        } catch (IOException e) {
            return null;
        }
    }

    /** Re-encodes an ordinary string literal, when its old raw text is exactly what this encoder writes. */
    static String reencodeString(String raw, String value) {
        String old = javaValue(raw);
        if (old == null || !encodeString(old).equals(raw)) return null;
        return encodeString(value);
    }

    static String encodeString(String value) {
        var sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

}
