package org.julclang.difftest;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DefaultCaseLabelTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.PatternCaseLabelTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import org.julclang.difftest.gen.G1;

import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G1's {@code switch} shapes read from a printed program with javac's parser (ADR-066 D8; review of M3 part 1, F75,
 * F97): independent of the generator's markers and of how the printer breaks lines.
 */
final class G1Source {

    /** The features this reading decides; for each, a program has it exactly when the generator marks it. */
    static final Set<G1.Feature> FEATURES = Collections.unmodifiableSet(EnumSet.of(G1.Feature.SWITCH,
            G1.Feature.NESTED_SWITCH, G1.Feature.YIELD_BLOCK, G1.Feature.LOOP_IN_ARM, G1.Feature.SWITCH_IN_LOOP,
            G1.Feature.TYPE_PATTERN, G1.Feature.DEFAULT_ARM, G1.Feature.BINDING_ACCUMULATOR,
            G1.Feature.ASSIGN_PATTERN_BINDING, G1.Feature.DEFAULT_BLOCK_SHADOW, G1.Feature.DEFAULT_BLOCK_LOOPS));

    /**
     * @param features     the {@link #FEATURES} the program has
     * @param armsPermuted whether some switch lists its variants in an order other than both their {@code permits}
     *                     order and their declaration order
     * @param violations   switches in a method receiver or in a test, and arms that assign a variable they do not
     *                     declare
     */
    record Reading(Set<G1.Feature> features, boolean armsPermuted, List<String> violations) {
    }

    private static final JavaCompiler JAVAC = ToolProvider.getSystemJavaCompiler();
    private static final StandardJavaFileManager FILES = JAVAC.getStandardFileManager(null, null, StandardCharsets.UTF_8);

    static Reading read(String source) {
        var file = new SimpleJavaFileObject(URI.create("string:///Probe.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var task = (JavacTask) JAVAC.getTask(null, FILES, diagnostic -> {
        }, List.of("-proc:none"), null, List.of(file));
        CompilationUnitTree unit;
        try {
            unit = task.parse().iterator().next();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var reader = new Reader();
        reader.scan(unit, null);
        return new Reading(reader.features, reader.armsPermuted, reader.violations);
    }

    private static final class Reader extends TreePathScanner<Void, Void> {
        final Set<G1.Feature> features = EnumSet.noneOf(G1.Feature.class);
        final List<String> violations = new ArrayList<>();
        boolean armsPermuted;
        final Set<String> fields = new HashSet<>();
        final Map<String, List<String>> permits = new HashMap<>();
        final Map<String, String> family = new HashMap<>();
        final Map<String, List<String>> declared = new HashMap<>();

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            if (getCurrentPath().getParentPath().getLeaf() instanceof CompilationUnitTree) {
                for (Tree member : node.getMembers()) {
                    if (member instanceof VariableTree field && field.getModifiers().getFlags().contains(Modifier.STATIC)) {
                        fields.add(field.getName().toString());
                    }
                    if (!(member instanceof ClassTree type)) continue;
                    String name = type.getSimpleName().toString();
                    if (!type.getPermitsClause().isEmpty()) {
                        permits.put(name, type.getPermitsClause().stream().map(Object::toString).toList());
                    }
                    for (Tree implemented : type.getImplementsClause()) {
                        family.put(name, implemented.toString());
                        declared.computeIfAbsent(implemented.toString(), k -> new ArrayList<>()).add(name);
                    }
                }
            }
            return super.visitClass(node, unused);
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
            features.add(G1.Feature.SWITCH);
            boolean inArgument = false;
            Tree child = node;
            for (TreePath path = getCurrentPath().getParentPath(); path != null; path = path.getParentPath()) {
                Tree tree = path.getLeaf();
                Tree parent = path.getParentPath() == null ? null : path.getParentPath().getLeaf();
                if (tree instanceof CaseTree) features.add(G1.Feature.NESTED_SWITCH);
                if (loop(tree)) features.add(G1.Feature.SWITCH_IN_LOOP);
                if (tree instanceof IfTree i && i.getCondition() == child
                        || tree instanceof ConditionalExpressionTree c && c.getCondition() == child
                        || tree instanceof WhileLoopTree w && w.getCondition() == child
                        || tree instanceof ForLoopTree f && f.getCondition() == child) {
                    violations.add("a switch in a test: " + tree);
                }
                Tree below = child;
                if (tree instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree
                        && call.getArguments().stream().anyMatch(argument -> argument == below)) {
                    inArgument = true;
                }
                if (tree instanceof MemberSelectTree select && select.getExpression() == child
                        && parent instanceof MethodInvocationTree call && call.getMethodSelect() == select) {
                    violations.add("a switch in a method receiver: " + parent);
                }
                child = tree;
            }
            var listed = new ArrayList<String>();
            for (CaseTree arm : node.getCases()) {
                String binding = null;
                for (var label : arm.getLabels()) {
                    if (label instanceof PatternCaseLabelTree pattern && pattern.getPattern() instanceof BindingPatternTree bound) {
                        features.add(G1.Feature.TYPE_PATTERN);
                        binding = bound.getVariable().getName().toString();
                        listed.add(bound.getVariable().getType().toString());
                    }
                    if (label instanceof DefaultCaseLabelTree) features.add(G1.Feature.DEFAULT_ARM);
                }
                arm(arm, binding, inArgument);
            }
            if (listed.size() > 1 && family.containsKey(listed.getFirst())) {
                String sealed = family.get(listed.getFirst());
                var byPermits = permits.getOrDefault(sealed, List.of()).stream().filter(listed::contains).toList();
                var byDeclaration = declared.getOrDefault(sealed, List.of()).stream().filter(listed::contains).toList();
                if (!listed.equals(byPermits) && !listed.equals(byDeclaration)) armsPermuted = true;
            }
            return super.visitSwitchExpression(node, unused);
        }

        /**
         * An arm's own shapes; {@code binding} is null for {@code default}, and {@code inArgument} says whether the
         * switch is inside an argument of a method call.
         */
        private void arm(CaseTree arm, String binding, boolean inArgument) {
            Tree body = arm.getBody();
            var names = new HashSet<String>();
            var assigned = new ArrayList<String>();
            var loops = new boolean[1];
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    names.add(node.getName().toString());
                    return super.visitVariable(node, unused);
                }

                @Override
                public Void visitAssignment(AssignmentTree node, Void unused) {
                    assigned.add(target(node.getVariable()));
                    return super.visitAssignment(node, unused);
                }

                @Override
                public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                    assigned.add(target(node.getVariable()));
                    return super.visitCompoundAssignment(node, unused);
                }

                @Override
                public Void visitWhileLoop(WhileLoopTree node, Void unused) {
                    loops[0] = true;
                    return super.visitWhileLoop(node, unused);
                }

                @Override
                public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
                    loops[0] = true;
                    return super.visitEnhancedForLoop(node, unused);
                }

                @Override
                public Void visitForLoop(ForLoopTree node, Void unused) {
                    loops[0] = true;
                    return super.visitForLoop(node, unused);
                }
            }.scan(body, null);
            if (loops[0]) features.add(G1.Feature.LOOP_IN_ARM);
            for (String target : assigned) {
                if (target.equals(binding)) features.add(G1.Feature.ASSIGN_PATTERN_BINDING);
                else if (!names.contains(target)) violations.add("an arm assigns " + target + ", which it does not declare");
            }
            if (!(body instanceof BlockTree block)) return;
            features.add(G1.Feature.YIELD_BLOCK);
            if (binding == null && inArgument && block.getStatements().stream().filter(Reader::loop).count() > 1) {
                features.add(G1.Feature.DEFAULT_BLOCK_LOOPS);
            }
            for (StatementTree statement : block.getStatements()) {
                if (!(statement instanceof VariableTree local)) continue;
                String name = local.getName().toString();
                if (binding == null && fields.contains(name)) features.add(G1.Feature.DEFAULT_BLOCK_SHADOW);
                if (binding != null && local.getInitializer() instanceof IdentifierTree init
                        && init.getName().contentEquals(binding) && assigned.contains(name)) {
                    features.add(G1.Feature.BINDING_ACCUMULATOR);
                }
            }
        }

        private static String target(ExpressionTree variable) {
            return variable instanceof IdentifierTree identifier ? identifier.getName().toString() : variable.toString();
        }

        private static boolean loop(Tree tree) {
            return tree instanceof WhileLoopTree || tree instanceof EnhancedForLoopTree || tree instanceof ForLoopTree;
        }
    }
}
