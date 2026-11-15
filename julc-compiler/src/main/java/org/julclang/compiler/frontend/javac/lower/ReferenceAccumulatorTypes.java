package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.subset.LoopRules;
import org.julclang.compiler.pir.PirType;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * What the reference reads a Data accumulator of a {@code while} as, when several travel in a tuple: {@code AccumulatorTypeAnalyzer}
 * on javac's trees, since that one reads JavaParser's. Its reading of a call by its name is, here, the element javac bound, and
 * the reference's gate holds a program to the same answer ({@code JULC0101}). {@code JAVAC} never reads less than this
 * ({@link AccumulatorTypes}). This builds no term, so it has no evaluation order to state.
 */
final class ReferenceAccumulatorTypes {

    private static final PirType MAP = new PirType.MapType(new PirType.DataType(), new PirType.DataType());
    private static final PirType LIST = new PirType.ListType(new PirType.DataType());
    private static final String MAP_DATA = JavacNames.PLUTUS_DATA + ".MapData";

    private final JavacFacts facts;

    ReferenceAccumulatorTypes(JavacFacts facts) {
        this.facts = facts;
    }

    /**
     * @param accumulators the loop's, in order
     * @param types        their declared types, in order
     * @param preceding    the statements before the loop in its list
     * @return their types
     */
    List<PirType> refined(WhileLoopTree loop, List<Element> accumulators, List<PirType> types, List<? extends StatementTree> preceding) {
        var pairLists = pairLists(loop, preceding);
        var result = new ArrayList<>(types);
        var body = loop.getStatement();
        for (int i = 0; i < accumulators.size(); i++) {
            if (!(result.get(i) instanceof PirType.DataType)) continue;
            var accumulator = accumulators.get(i);
            boolean tail = assigns(body, accumulator, "tailList");
            boolean nil = assigns(body, accumulator, "mkNilData");
            boolean cons = assigns(body, accumulator, "mkCons");
            if (startsAsPairList(accumulator, preceding, pairLists) || assigns(body, accumulator, "mkNilPairData")) {
                result.set(i, MAP);
            } else if (tail || nil || cons) {
                boolean pairs = cons ? callsAnywhere(body, "mkPairData") : tail && !nil && readsPairsOf(body, accumulator);
                result.set(i, pairs ? MAP : LIST);
            }
        }
        // A cursor beside a pair list walks one, so it is one too.
        if (result.stream().anyMatch(type -> type instanceof PirType.MapType)) {
            for (int i = 0; i < accumulators.size(); i++) {
                if (result.get(i) instanceof PirType.ListType && assigns(body, accumulators.get(i), "tailList")
                        && !assigns(body, accumulators.get(i), "mkCons")) {
                    result.set(i, MAP);
                }
            }
        }
        return result;
    }

    /** The variables known to hold a pair list: parameters of the method declared so, and locals before the loop. */
    private Set<Element> pairLists(WhileLoopTree loop, List<? extends StatementTree> preceding) {
        var names = new HashSet<Element>();
        var method = (MethodTree) facts.enclosing(loop, Tree.Kind.METHOD);
        if (method != null) {
            for (var parameter : method.getParameters()) {
                if (isMapData(parameter)) names.add(facts.element(parameter));
            }
        }
        for (var statement : preceding) {
            if (statement instanceof VariableTree local && local.getInitializer() != null) {
                var value = local.getInitializer();
                if (isPairListCall(value) || isMapData(local)) names.add(facts.element(local));
            }
        }
        return names;
    }

    /** Whether {@code variable} is written {@code MapData}: the reference reads the written type, which a {@code var} has none of. */
    private boolean isMapData(VariableTree variable) {
        if (facts.isInferred(variable)) return false;
        var type = facts.element(variable).asType();
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).equals(MAP_DATA);
    }

    private boolean isPairListCall(ExpressionTree tree) {
        return isBuiltin(tree, "mkNilPairData") || isBuiltin(tree, "unMapData");
    }

    /** Whether {@code tree} is a call of the builtin {@code name}. */
    private boolean isBuiltin(ExpressionTree tree, String name) {
        return tree instanceof MethodInvocationTree call && facts.element(call.getMethodSelect()) instanceof ExecutableElement method
                && method.getSimpleName().contentEquals(name) && method.getEnclosingElement() instanceof TypeElement owner
                && facts.identity(owner).equals(JavacNames.BUILTINS);
    }

    /** The initializer of {@code variable} among {@code preceding}, or null. */
    private ExpressionTree initializer(Element variable, List<? extends StatementTree> preceding) {
        for (var statement : preceding) {
            if (statement instanceof VariableTree local && facts.element(local).equals(variable)) return local.getInitializer();
        }
        return null;
    }

    private boolean startsAsPairList(Element accumulator, List<? extends StatementTree> preceding, Set<Element> pairLists) {
        if (pairLists.contains(accumulator)) return true;
        for (var value = initializer(accumulator, preceding); value != null; value = value instanceof IdentifierTree name
                ? initializer(facts.element(name), preceding) : null) {
            if (isPairListCall(value) || value instanceof IdentifierTree name && pairLists.contains(facts.element(name))) return true;
        }
        return false;
    }

    /** Whether a statement of {@code body}, in its blocks and branches, sets {@code accumulator} to a call of the builtin. */
    private boolean assigns(StatementTree body, Element accumulator, String builtin) {
        return firstOf(body, statement -> statement instanceof ExpressionStatementTree expression
                && expression.getExpression() instanceof AssignmentTree assignment
                && assignment.getVariable() instanceof IdentifierTree target && facts.element(target).equals(accumulator)
                && isBuiltin(assignment.getExpression(), builtin) ? statement : null) != null;
    }

    /**
     * The first non-null {@code test} of a statement of {@code body} that is neither a block nor an {@code if}, in source
     * order through blocks and both branches of each {@code if}. A work list, not recursion: an {@code else if} chain is as
     * deep as it is long (issue #327).
     */
    private static <T> T firstOf(StatementTree body, Function<StatementTree, T> test) {
        var pending = new ArrayDeque<StatementTree>();
        pending.push(body);
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case BlockTree block -> {
                    var statements = block.getStatements();
                    for (int i = statements.size() - 1; i >= 0; i--) pending.push(statements.get(i));
                }
                case IfTree branch -> {
                    if (branch.getElseStatement() != null) pending.push(branch.getElseStatement());
                    pending.push(branch.getThenStatement());
                }
                case StatementTree statement -> {
                    var found = test.apply(statement);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private boolean callsAnywhere(Tree body, String builtin) {
        return LoopRules.calls(body).stream().anyMatch(call -> isBuiltin(call, builtin));
    }

    /** Whether the element a cursor's head goes to is read as a pair in {@code body}. */
    private boolean readsPairsOf(StatementTree body, Element cursor) {
        var head = headOf(body, cursor);
        if (head == null) return false;
        for (var call : LoopRules.calls(body)) {
            if ((isBuiltin(call, "fstPair") || isBuiltin(call, "sndPair"))
                    && call.getArguments().getFirst() instanceof IdentifierTree name && facts.element(name).equals(head)) {
                return true;
            }
        }
        return false;
    }

    /** The first local of {@code statement} initialized to {@code headList(cursor)}, or null. */
    private Element headOf(StatementTree statement, Element cursor) {
        return firstOf(statement, inner -> inner instanceof VariableTree local && isBuiltin(local.getInitializer(), "headList")
                && ((MethodInvocationTree) local.getInitializer()).getArguments().getFirst() instanceof IdentifierTree name
                && facts.element(name).equals(cursor) ? facts.element(local) : null);
    }
}
