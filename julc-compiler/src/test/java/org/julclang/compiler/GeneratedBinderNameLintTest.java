package org.julclang.compiler;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-060 G3: every PIR binder name the compiler invents must begin with {@code #}. The lint parses
 * the compiler and stdlib sources with javac (JavaParser cannot read all of them) and follows every
 * name that reaches a PIR binder constructor or name allocator back to where it is made:
 * <ul>
 *   <li>a string literal, or a concatenation whose leftmost operand is a literal, must begin with
 *       {@code #}; a leading {@code .} marks a member-access pseudo-variable, which is a reference;
 *   <li>a concatenation of a source name with the block-local mark {@code '} or a qualifier
 *       {@code .} is a source-derived name;
 *   <li>a local variable is checked through its initializer and every assignment, a loop variable
 *       over a literal list through the list's elements, and a constant through its initializer;
 *   <li>a parameter makes every argument passed for it, at every call site in the sources, a
 *       checked name (to a fixed point); a call to a method declared in the sources checks that
 *       method's {@code return} expressions;
 *   <li>any other call is accepted only if it is a known accessor that copies a name the compiler
 *       did not invent ({@link #ACCESSORS}).
 * </ul>
 */
class GeneratedBinderNameLintTest {
    /** Constructor type to the argument positions holding binder names. */
    private static final Map<String, List<Integer>> BINDER_ARGUMENTS = Map.of(
            "PirTerm.Var", List.of(0),
            "PirTerm.Lam", List.of(0),
            "PirTerm.Let", List.of(0),
            "PirTerm.Binding", List.of(0),
            "PirTerm.ListMatch", List.of(1, 2),
            "PirTerm.PairMatch", List.of(2, 3),
            "PirTerm.MatchBranch", List.of(4));
    /** Allocators whose first argument becomes a generated binder name. */
    private static final Set<String> ALLOCATORS = Set.of("hygienicName", "nextLoopName", "recursiveListGetBinding");
    /**
     * Calls, not declared in the scanned sources, that copy an existing name: a source
     * declaration's name (JavaParser accessors) or a PIR node's own binder name (record accessors).
     */
    private static final Set<String> ACCESSORS = Set.of(
            "getNameAsString", "getIdentifier", "asString", "name", "param", "parameter", "binderName",
            "qualifiedName", "patternVar", "headName", "tailName", "firstName", "secondName", "get", "getFirst",
            "orElse", "orElseThrow");

    private record Context(CompilationUnitTree unit, MethodTree method) {}

    /** A name to check; {@code hash} requires it to begin with {@code #}, not merely be a source name. */
    private record Site(Context context, ExpressionTree name, boolean hash) {
        Site(Context context, ExpressionTree name) { this(context, name, false); }
    }

    private record MethodRef(CompilationUnitTree unit, MethodTree method) {}

    private final List<CompilationUnitTree> units = new ArrayList<>();
    private SourcePositions positions;
    private final Map<String, List<MethodRef>> declared = new HashMap<>();
    private final List<Site> calls = new ArrayList<>();
    private final Set<String> binderParameters = new HashSet<>();
    private final Set<String> checkedReturns = new HashSet<>();
    private final Deque<Site> work = new ArrayDeque<>();
    private final Set<String> violations = new LinkedHashSet<>();
    private int checked;

    @Test
    void generatedBinderNamesAreReserved() throws IOException {
        parse();
        for (var unit : units) collect(unit);
        while (!work.isEmpty()) {
            var site = work.pop();
            checked++;
            if (!reserved(site.name(), site.context(), site.hash(), 0)) violations.add(location(site));
        }
        assertTrue(checked > 250, "lint checked too few binder names: " + checked);
        assertTrue(violations.isEmpty(), "generated binder names outside the reserved # namespace:\n"
                + String.join("\n", violations));
    }

    private void parse() throws IOException {
        var files = new ArrayList<Path>();
        for (var root : List.of(Path.of("src/main/java"), Path.of("../julc-stdlib/src/main/java")))
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
            }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        var task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null,
                fileManager.getJavaFileObjectsFromPaths(files));
        task.parse().forEach(units::add);
        assertTrue(diagnostics.getDiagnostics().isEmpty(), () -> "cannot lint: " + diagnostics.getDiagnostics());
        positions = Trees.instance(task).getSourcePositions();
    }

    /** Index the declared methods and call sites, and queue every binder-name argument. */
    private void collect(CompilationUnitTree unit) {
        new TreePathScanner<Void, Void>() {
            @Override public Void visitMethod(MethodTree method, Void unused) {
                declared.computeIfAbsent(key(method.getName().toString(), method.getParameters().size()),
                        k -> new ArrayList<>()).add(new MethodRef(unit, method));
                return super.visitMethod(method, unused);
            }
            @Override public Void visitNewClass(NewClassTree creation, Void unused) {
                var argumentPositions = BINDER_ARGUMENTS.get(creation.getIdentifier().toString());
                if (argumentPositions != null)
                    for (int position : argumentPositions)
                        if (position < creation.getArguments().size())
                            work.push(new Site(context(getCurrentPath()), creation.getArguments().get(position)));
                return super.visitNewClass(creation, unused);
            }
            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                var context = context(getCurrentPath());
                if (ALLOCATORS.contains(name(call)) && !call.getArguments().isEmpty())
                    work.push(new Site(context, call.getArguments().getFirst()));
                calls.add(new Site(context, call));
                return super.visitMethodInvocation(call, unused);
            }
        }.scan(unit, null);
    }

    /**
     * Whether {@code name} is safe as a binder name: a reserved {@code #} name, or, unless
     * {@code hash} is set, an unchanged or marked source name.
     */
    private boolean reserved(ExpressionTree name, Context context, boolean hash, int depth) {
        if (depth > 12) return false;
        return switch (name) {
            case ParenthesizedTree p -> reserved(p.getExpression(), context, hash, depth + 1);
            case ConditionalExpressionTree c -> reserved(c.getTrueExpression(), context, hash, depth + 1)
                    && reserved(c.getFalseExpression(), context, hash, depth + 1);
            // null binds no name (an absent pattern variable).
            case LiteralTree literal -> literal.getValue() == null
                    || literal.getValue() instanceof String s && (s.startsWith("#") || !hash && s.startsWith("."));
            case BinaryTree binary when binary.getKind() == Tree.Kind.PLUS -> {
                // "#..." + x is reserved; source + "'" + n and qualifier + "." + name are source-derived;
                // any other concatenation (source + "__raw") invents a legal Java identifier.
                var leftmost = leftmost(binary);
                if (leftmost instanceof LiteralTree literal && literal.getValue() instanceof String s)
                    yield s.startsWith("#") || !hash && s.equals(".");
                yield !hash && containsMark(binary) || reserved(leftmost, context, true, depth + 1);
            }
            case IdentifierTree identifier -> identifierReserved(identifier, context, hash, depth);
            case MethodInvocationTree call -> callReserved(call, context, hash);
            // Field reads: record components (pf.name) and constants of other classes.
            case MemberSelectTree _ -> true;
            default -> false;
        };
    }

    private boolean identifierReserved(IdentifierTree identifier, Context context, boolean hash, int depth) {
        String name = identifier.getName().toString();
        if (context.method() != null) {
            long use = positions.getStartPosition(context.unit(), identifier);
            var parameters = context.method().getParameters();
            VariableTree[] local = {null};
            ExpressionTree[] iterable = {null};
            var assigned = new ArrayList<ExpressionTree>();
            new TreeScanner<Void, Void>() {
                @Override public Void visitVariable(VariableTree variable, Void unused) {
                    if (variable.getName().contentEquals(name) && !parameters.contains(variable)
                            && positions.getStartPosition(context.unit(), variable) < use) local[0] = variable;
                    return super.visitVariable(variable, unused);
                }
                @Override public Void visitEnhancedForLoop(EnhancedForLoopTree loop, Void unused) {
                    if (loop.getVariable().getName().contentEquals(name)) iterable[0] = loop.getExpression();
                    return super.visitEnhancedForLoop(loop, unused);
                }
                @Override public Void visitAssignment(AssignmentTree assignment, Void unused) {
                    if (assignment.getVariable() instanceof IdentifierTree target && target.getName().contentEquals(name))
                        assigned.add(assignment.getExpression());
                    return super.visitAssignment(assignment, unused);
                }
            }.scan(context.method().getBody(), null);
            if (local[0] != null) {
                if (iterable[0] != null)
                    return literalElements(iterable[0]).stream().allMatch(e -> reserved(e, context, hash, depth + 1));
                var values = new ArrayList<ExpressionTree>(assigned);
                var initializer = local[0].getInitializer();
                if (initializer != null && !(initializer instanceof LiteralTree l && l.getValue() == null)) values.add(initializer);
                // A lambda parameter has neither: it is made where the lambda is called.
                return values.isEmpty() ? !hash : values.stream().allMatch(v -> reserved(v, context, hash, depth + 1));
            }
            for (int i = 0; i < parameters.size(); i++)
                if (parameters.get(i).getName().contentEquals(name)) {
                    markBinderParameter(new MethodRef(context.unit(), context.method()), i, hash);
                    return true;
                }
        }
        var constant = constant(context.unit(), name);
        if (constant != null) return reserved(constant, new Context(context.unit(), null), hash, depth + 1);
        // A name this lint cannot resolve is made elsewhere and checked there.
        return !hash;
    }

    private boolean callReserved(MethodInvocationTree call, Context context, boolean hash) {
        String name = name(call);
        if (ALLOCATORS.contains(name)) return true;
        var targets = targets(call, context);
        if (!targets.isEmpty()) {
            for (var target : targets) checkReturns(target, hash);
            return true;
        }
        return !hash && ACCESSORS.contains(name);
    }

    /** A name made by a method declared in the sources is checked where the method returns it. */
    private void checkReturns(MethodRef target, boolean hash) {
        var method = target.method();
        if (method.getReturnType() == null || !method.getReturnType().toString().equals("String")) return;
        if (!checkedReturns.add(identity(target) + hash)) return;
        var context = new Context(target.unit(), method);
        new TreeScanner<Void, Void>() {
            @Override public Void visitReturn(ReturnTree ret, Void unused) {
                if (ret.getExpression() != null) work.push(new Site(context, ret.getExpression(), hash));
                return super.visitReturn(ret, unused);
            }
            @Override public Void visitClass(ClassTree cls, Void unused) { return null; }
            @Override public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) { return null; }
        }.scan(method.getBody(), null);
    }

    /** A parameter that becomes a binder name: every argument passed for it is checked. */
    private void markBinderParameter(MethodRef method, int index, boolean hash) {
        if (!binderParameters.add(identity(method) + "#" + index + hash)) return;
        String name = method.method().getName().toString();
        int arity = method.method().getParameters().size();
        for (var site : calls) {
            var call = (MethodInvocationTree) site.name();
            if (name(call).equals(name) && call.getArguments().size() == arity
                    && targets(call, site.context()).contains(method)
                    && !notAString(call.getArguments().get(index), site.context()))
                work.push(new Site(site.context(), call.getArguments().get(index), hash));
        }
    }

    /**
     * Whether an argument evidently has a type other than {@code String}, so the call selects an
     * overload whose parameter at this position is not a name.
     */
    private boolean notAString(ExpressionTree argument, Context context) {
        if (argument instanceof NewClassTree) return true;
        if (argument instanceof IdentifierTree identifier) {
            String[] type = {null};
            new TreeScanner<Void, Void>() {
                @Override public Void visitVariable(VariableTree variable, Void unused) {
                    if (variable.getName().contentEquals(identifier.getName()) && type[0] == null && variable.getType() != null)
                        type[0] = variable.getType().toString();
                    return super.visitVariable(variable, unused);
                }
            }.scan(context.unit(), null);
            return type[0] != null && !type[0].equals("String") && !type[0].equals("var");
        }
        return false;
    }

    /**
     * The methods declared in the sources that {@code call} can invoke: in the caller's class for an
     * unqualified or {@code this} call, else in the class the qualifier names or the class of the
     * receiver variable's declared type.
     */
    private List<MethodRef> targets(MethodInvocationTree call, Context context) {
        var candidates = declared.getOrDefault(key(name(call), call.getArguments().size()), List.of());
        if (candidates.isEmpty()) return List.of();
        String owner = switch (call.getMethodSelect()) {
            case IdentifierTree _ -> null;
            case MemberSelectTree select when select.getExpression() instanceof IdentifierTree receiver ->
                    receiver.getName().contentEquals("this") ? null : receiverClass(receiver, context);
            default -> "";
        };
        if (owner == null) return candidates.stream().filter(c -> c.unit() == context.unit()).toList();
        return candidates.stream().filter(c -> declaringClasses(c).contains(owner)).toList();
    }

    /** The simple class name a receiver denotes: itself if it is a class, else its variable's type. */
    private String receiverClass(IdentifierTree receiver, Context context) {
        String name = receiver.getName().toString();
        String[] type = {null};
        new TreeScanner<Void, Void>() {
            @Override public Void visitVariable(VariableTree variable, Void unused) {
                if (variable.getName().contentEquals(name) && type[0] == null)
                    type[0] = variable.getType() == null ? null : variable.getType().toString().replaceAll("<.*", "");
                return super.visitVariable(variable, unused);
            }
        }.scan(context.unit(), null);
        return type[0] != null && !type[0].equals("var") ? type[0] : name;
    }

    private Set<String> declaringClasses(MethodRef ref) {
        var names = new HashSet<String>();
        new TreePathScanner<Void, Void>() {
            @Override public Void visitMethod(MethodTree method, Void unused) {
                if (method == ref.method())
                    for (var p = getCurrentPath(); p != null; p = p.getParentPath())
                        if (p.getLeaf() instanceof ClassTree cls) names.add(cls.getSimpleName().toString());
                return null;
            }
        }.scan(ref.unit(), null);
        return names;
    }

    private List<ExpressionTree> literalElements(ExpressionTree iterable) {
        if (iterable instanceof MethodInvocationTree call && Set.of("of", "asList").contains(name(call)))
            return new ArrayList<>(call.getArguments());
        if (iterable instanceof NewArrayTree array && array.getInitializers() != null)
            return new ArrayList<>(array.getInitializers());
        return List.of();
    }

    private ExpressionTree constant(CompilationUnitTree unit, String name) {
        ExpressionTree[] found = {null};
        new TreeScanner<Void, Void>() {
            @Override public Void visitClass(ClassTree cls, Void unused) {
                for (var member : cls.getMembers())
                    if (member instanceof VariableTree field && field.getName().contentEquals(name) && found[0] == null)
                        found[0] = field.getInitializer();
                return super.visitClass(cls, unused);
            }
        }.scan(unit, null);
        return found[0];
    }

    private static ExpressionTree leftmost(BinaryTree binary) {
        ExpressionTree left = binary.getLeftOperand();
        while (true) {
            if (left instanceof BinaryTree inner && inner.getKind() == Tree.Kind.PLUS) left = inner.getLeftOperand();
            else if (left instanceof ParenthesizedTree p) left = p.getExpression();
            else return left;
        }
    }

    private static boolean containsMark(Tree tree) {
        boolean[] found = {false};
        new TreeScanner<Void, Void>() {
            @Override public Void visitLiteral(LiteralTree literal, Void unused) {
                if (literal.getValue() instanceof String s && (s.equals("'") || s.equals("."))) found[0] = true;
                return null;
            }
            @Override public Void visitIdentifier(IdentifierTree identifier, Void unused) {
                if (identifier.getName().contentEquals("BLOCK_LOCAL_MARK")) found[0] = true;
                return null;
            }
        }.scan(tree, null);
        return found[0];
    }

    private static Context context(TreePath path) {
        MethodTree method = null;
        for (var p = path; p != null; p = p.getParentPath())
            if (p.getLeaf() instanceof MethodTree m) { method = m; break; }
        return new Context(path.getCompilationUnit(), method);
    }

    private static String name(MethodInvocationTree call) {
        return switch (call.getMethodSelect()) {
            case MemberSelectTree select -> select.getIdentifier().toString();
            case IdentifierTree identifier -> identifier.getName().toString();
            default -> "";
        };
    }

    private static String key(String method, int arity) {
        return method + "/" + arity;
    }

    private String identity(MethodRef ref) {
        return ref.unit().getSourceFile().getName() + "@" + positions.getStartPosition(ref.unit(), ref.method());
    }

    private String location(Site site) {
        var unit = site.context().unit();
        long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, site.name()));
        return Path.of(unit.getSourceFile().toUri()).getFileName() + ":" + line + " " + site.name();
    }
}
