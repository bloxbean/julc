package org.julclang.compiler;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-060 G3: every PIR binder name the compiler invents must begin with {@code #}. This lint parses
 * the compiler and stdlib sources with javac and checks the name argument of every PIR binder
 * constructor and name allocator. A name is accepted when it is (1) a literal, or a concatenation
 * whose leftmost operand is a literal, beginning with {@code #} (a leading {@code .} marks a
 * member-access pseudo-variable, which is a reference, not a binder); (2) copied from a declaration
 * or another checked name, such as {@code decl.getNameAsString()}, {@code binding.name()} or a
 * parameter; or (3) a source name carrying the block-local mark {@code '} or qualified with
 * {@code "."}. Local variables and constants are resolved to their initializers, and both branches
 * of a conditional are checked.
 */
class GeneratedBinderNameLintTest {
    /** Constructor type to the argument positions holding binder names. */
    private static final Map<String, List<Integer>> BINDER_ARGUMENTS = Map.of(
            "PirTerm.Var", List.of(0),
            "PirTerm.Lam", List.of(0),
            "PirTerm.Let", List.of(0),
            "PirTerm.Binding", List.of(0),
            "PirTerm.ListMatch", List.of(1, 2),
            "PirTerm.PairMatch", List.of(2, 3));
    /** Allocators whose first argument becomes a generated binder name. */
    private static final Set<String> ALLOCATORS = Set.of("hygienicName", "nextLoopName", "recursiveListGetBinding");
    /** String parameters that become binder names in the method declaring them. */
    private static final Pattern BINDER_PARAMETER = Pattern.compile(
            "(acc|item|decoded|bound|binder|local|tuple\\w*|head|tail|go|lst)Name|binder|boundName");
    /** Separators that make a name derived from a source name (block-local rename, qualification). */
    private static final Set<String> DERIVED_MARKS = Set.of("'", ".");

    @Test
    void generatedBinderNamesAreReserved() throws IOException {
        var files = new ArrayList<Path>();
        for (var root : List.of(Path.of("src/main/java"), Path.of("../julc-stdlib/src/main/java")))
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
            }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null,
                    fileManager.getJavaFileObjectsFromPaths(files));
            var units = task.parse();
            assertTrue(diagnostics.getDiagnostics().isEmpty(), () -> "cannot lint: " + diagnostics.getDiagnostics());
            var positions = Trees.instance(task).getSourcePositions();
            var violations = new ArrayList<String>();
            int[] checked = {0};
            for (var unit : units) new Lint(unit, positions, violations, checked).scan(unit, null);
            assertTrue(checked[0] > 200, "lint found too few binder names: " + checked[0]);
            assertTrue(violations.isEmpty(), "generated binder names outside the reserved # namespace:\n"
                    + String.join("\n", violations));
        }
    }

    private static final class Lint extends TreeScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final List<String> violations;
        private final int[] checked;
        private final Deque<MethodTree> methods = new ArrayDeque<>();
        private final Map<String, ExpressionTree> constants = new HashMap<>();
        private final Map<String, List<Integer>> nameParameters = new HashMap<>();

        Lint(CompilationUnitTree unit, SourcePositions positions, List<String> violations, int[] checked) {
            this.unit = unit;
            this.positions = positions;
            this.violations = violations;
            this.checked = checked;
            new TreeScanner<Void, Void>() {
                @Override public Void visitClass(ClassTree cls, Void unused) {
                    for (var member : cls.getMembers())
                        if (member instanceof VariableTree field && field.getInitializer() != null)
                            constants.putIfAbsent(field.getName().toString(), field.getInitializer());
                    return super.visitClass(cls, unused);
                }
                @Override public Void visitMethod(MethodTree method, Void unused) {
                    var params = new ArrayList<Integer>();
                    for (int i = 0; i < method.getParameters().size(); i++) {
                        var param = method.getParameters().get(i);
                        if (param.getType().toString().equals("String")
                                && BINDER_PARAMETER.matcher(param.getName().toString()).matches()) params.add(i);
                    }
                    if (!params.isEmpty())
                        nameParameters.putIfAbsent(method.getName() + "/" + method.getParameters().size(), params);
                    return super.visitMethod(method, unused);
                }
            }.scan(unit, null);
        }

        @Override public Void visitMethod(MethodTree method, Void unused) {
            methods.push(method);
            try { return super.visitMethod(method, unused); } finally { methods.pop(); }
        }

        @Override public Void visitNewClass(NewClassTree creation, Void unused) {
            var argumentPositions = BINDER_ARGUMENTS.get(creation.getIdentifier().toString());
            if (argumentPositions != null)
                for (int position : argumentPositions)
                    if (position < creation.getArguments().size()) check(creation.getArguments().get(position));
            return super.visitNewClass(creation, unused);
        }

        @Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            String name = switch (call.getMethodSelect()) {
                case MemberSelectTree select -> select.getIdentifier().toString();
                case IdentifierTree identifier -> identifier.getName().toString();
                default -> "";
            };
            if (ALLOCATORS.contains(name) && !call.getArguments().isEmpty()) check(call.getArguments().getFirst());
            var params = nameParameters.get(name + "/" + call.getArguments().size());
            if (params != null)
                for (int position : params) {
                    var argument = call.getArguments().get(position);
                    if (argument instanceof LiteralTree || argument instanceof BinaryTree) check(argument);
                }
            return super.visitMethodInvocation(call, unused);
        }

        private void check(ExpressionTree name) {
            checked[0]++;
            if (!reserved(name, 0)) {
                long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, name));
                violations.add(Path.of(unit.getSourceFile().toUri()).getFileName() + ":" + line + " " + name);
            }
        }

        private boolean reserved(ExpressionTree name, int depth) {
            if (depth > 8) return false;
            return switch (name) {
                case ParenthesizedTree p -> reserved(p.getExpression(), depth + 1);
                case ConditionalExpressionTree c ->
                        reserved(c.getTrueExpression(), depth + 1) && reserved(c.getFalseExpression(), depth + 1);
                case LiteralTree literal -> literal.getValue() instanceof String s
                        && (s.startsWith("#") || s.startsWith("."));
                case BinaryTree binary when binary.getKind() == Tree.Kind.PLUS -> {
                    // "#..." + x is reserved; source + "'" + n and qualifier + "." + name are derived;
                    // anything else, such as source + "__raw", invents a legal Java identifier.
                    var leftmost = leftmost(binary);
                    if (leftmost instanceof LiteralTree literal && literal.getValue() instanceof String s)
                        yield s.startsWith("#") || s.equals(".");
                    yield containsMark(binary);
                }
                case IdentifierTree identifier -> {
                    // A parameter, lambda parameter or loop variable carries a name checked where it is made.
                    var values = valuesOf(identifier);
                    yield values.stream().allMatch(value -> reserved(value, depth + 1));
                }
                // Accessors (decl.getNameAsString(), binding.name()) and allocators checked at their call.
                default -> true;
            };
        }

        private ExpressionTree leftmost(BinaryTree binary) {
            ExpressionTree left = binary.getLeftOperand();
            while (true) {
                if (left instanceof BinaryTree inner && inner.getKind() == Tree.Kind.PLUS) left = inner.getLeftOperand();
                else if (left instanceof ParenthesizedTree p) left = p.getExpression();
                else return left;
            }
        }

        private boolean containsMark(Tree tree) {
            boolean[] found = {false};
            new TreeScanner<Void, Void>() {
                @Override public Void visitLiteral(LiteralTree literal, Void unused) {
                    if (literal.getValue() instanceof String s && DERIVED_MARKS.contains(s)) found[0] = true;
                    return null;
                }
                @Override public Void visitIdentifier(IdentifierTree identifier, Void unused) {
                    if (identifier.getName().contentEquals("BLOCK_LOCAL_MARK")) found[0] = true;
                    return null;
                }
            }.scan(tree, null);
            return found[0];
        }

        /**
         * Every value the name can hold at {@code use}: the non-null initializer of the last local
         * declared before it in its method and every assignment to that name there, else the
         * initializer of a constant. Empty for a parameter.
         */
        private List<ExpressionTree> valuesOf(IdentifierTree use) {
            String name = use.getName().toString();
            long usePosition = positions.getStartPosition(unit, use);
            VariableTree[] latest = {null};
            var assigned = new ArrayList<ExpressionTree>();
            if (!methods.isEmpty()) {
                new TreeScanner<Void, Void>() {
                    @Override public Void visitVariable(VariableTree variable, Void unused) {
                        if (variable.getName().contentEquals(name)
                                && positions.getStartPosition(unit, variable) < usePosition) latest[0] = variable;
                        return super.visitVariable(variable, unused);
                    }
                    @Override public Void visitAssignment(AssignmentTree assignment, Void unused) {
                        if (assignment.getVariable() instanceof IdentifierTree target && target.getName().contentEquals(name))
                            assigned.add(assignment.getExpression());
                        return super.visitAssignment(assignment, unused);
                    }
                }.scan(methods.peek(), null);
            }
            var values = new ArrayList<ExpressionTree>();
            if (latest[0] != null) {
                var initializer = latest[0].getInitializer();
                if (initializer != null && !(initializer instanceof LiteralTree literal && literal.getValue() == null))
                    values.add(initializer);
                values.addAll(assigned);
            } else if (constants.containsKey(name)) {
                values.add(constants.get(name));
            }
            return values;
        }
    }
}
