package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.SwitchResults;
import org.julclang.compiler.frontend.javac.subset.LoopRules;
import org.julclang.compiler.pir.PirType;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a {@code PlutusData} accumulator holds when several travel in a tuple. Its Java type does not say: a builtin leaves a
 * list or a pair list under it, and packing one as Data fails. So this reads what reaches the variable, from what javac bound:
 * its start and every assignment to it in the method, through parentheses, conditionals, a {@code switch}, other variables, and
 * the {@code return}s of a helper. A call is the element javac bound, so {@code tailList} is {@code Builtins.tailList} and
 * nothing else.
 * <p>
 * A variable is what the reference reads it as when that says a list or a map ({@link ReferenceAccumulatorTypes}), so this only
 * adds to the reference's reading, and a program that runs on the reference keeps it. Otherwise it is a list only if its start
 * and every value that reaches it are shown to be a list of one kind. Anything else is Data, and travels as Data: nothing known,
 * a mix with Data, a list and pairs. A list that then arrives in it fails on-chain. A cursor nothing else says of is a list,
 * unless what its head is read as, or a map cursor beside it, says pairs. This builds no term, so it has no evaluation order to
 * state.
 */
final class AccumulatorTypes {

    private static final PirType MAP = new PirType.MapType(new PirType.DataType(), new PirType.DataType());
    private static final PirType LIST = new PirType.ListType(new PirType.DataType());

    /** What is shown of a value. {@code SAME} is the variable being read, which says nothing of itself. */
    private enum Held {
        SAME, DATA, PAIR, CURSOR, LIST, PAIRS;

        boolean isList() {
            return this == CURSOR || this == LIST || this == PAIRS;
        }

        /** What a variable that may hold either holds. */
        Held and(Held other) {
            if (this == SAME || this == other) return other;
            if (other == SAME) return this;
            if (this == CURSOR && other.isList()) return other;
            if (other == CURSOR && isList()) return this;
            return DATA;
        }
    }

    private final JavacFacts facts;
    private final ReferenceAccumulatorTypes reference;

    AccumulatorTypes(JavacFacts facts) {
        this.facts = facts;
        this.reference = new ReferenceAccumulatorTypes(facts);
    }

    /**
     * @param preceding    what stands before the loop in the list it is lowered from
     * @param accumulators the loop's, in order
     * @param types        their declared types, in order
     * @return the types they travel at
     */
    List<PirType> travelling(StatementTree loop, List<? extends StatementTree> preceding, List<Element> accumulators, List<PirType> types) {
        var method = facts.enclosing(loop, Tree.Kind.METHOD);
        var reading = new Reading(method == null ? loop : method);
        var result = new ArrayList<>(loop instanceof WhileLoopTree tree ? reference.refined(tree, accumulators, types, preceding) : types);
        var held = new ArrayList<Held>();
        for (int i = 0; i < accumulators.size(); i++) {
            var what = result.get(i) instanceof PirType.DataType ? reading.variable(accumulators.get(i), new HashSet<>()) : Held.DATA;
            held.add(what);
            if (what == Held.PAIRS) result.set(i, MAP);
            if (what == Held.LIST) result.set(i, LIST);
        }
        // A cursor beside a pair list walks one, so it is one too.
        boolean pairs = result.stream().anyMatch(type -> type instanceof PirType.MapType);
        for (int i = 0; i < held.size(); i++) {
            if (held.get(i) == Held.CURSOR) result.set(i, pairs ? MAP : LIST);
        }
        return result;
    }

    private final class Reading {
        private final List<AssignmentTree> assignments = new ArrayList<>();
        private final List<MethodInvocationTree> calls;

        Reading(Tree root) {
            for (var update : LoopRules.updates(root)) {
                if (update instanceof AssignmentTree assignment) assignments.add(assignment);
            }
            calls = LoopRules.calls(root);
        }

        /** What {@code variable} is shown to hold. {@code read} holds what is being read. */
        Held variable(Element variable, Set<Element> read) {
            if (!read.add(variable)) return Held.SAME;
            var held = start(variable, read);
            for (var assignment : assignments) {
                if (assignment.getVariable() instanceof IdentifierTree target && variable.equals(facts.element(target))) {
                    held = held.and(value(assignment.getExpression(), read));
                }
            }
            return held == Held.CURSOR && headIsReadAsPair(variable) ? Held.PAIRS : held;
        }

        /**
         * What an initializer shows. A declared type says nothing: {@code PlutusData.ListData} and {@code MapData} are also
         * the types of encoded Data. A parameter's value is the caller's, and a {@code @Param}'s initializer can only be a literal.
         */
        private Held start(Element variable, Set<Element> read) {
            boolean initialized = variable instanceof VariableElement local && facts.initializer(local) != null;
            return initialized ? value(facts.initializer((VariableElement) variable), read) : Held.DATA;
        }

        private Held value(ExpressionTree tree, Set<Element> read) {
            return switch (tree) {
                case ParenthesizedTree parenthesized -> value(parenthesized.getExpression(), read);
                case ConditionalExpressionTree conditional ->
                        value(conditional.getTrueExpression(), read).and(value(conditional.getFalseExpression(), read));
                case SwitchExpressionTree selected -> {
                    var held = Held.SAME;
                    for (var result : SwitchResults.of(selected)) held = held.and(value(result, read));
                    yield held;
                }
                case IdentifierTree name when facts.elementOrNull(name) instanceof VariableElement variable -> variable(variable, read);
                case MethodInvocationTree call -> call(call, read);
                default -> Held.DATA;
            };
        }

        private Held call(MethodInvocationTree call, Set<Element> read) {
            if (!(facts.element(call.getMethodSelect()) instanceof ExecutableElement method)) return Held.DATA;
            if (!isBuiltin(method)) return helper(method, read);
            return switch (method.getSimpleName().toString()) {
                case "tailList" -> {
                    var list = value(call.getArguments().getFirst(), read);
                    yield list.isList() ? list : Held.CURSOR;
                }
                case "mkCons" -> {
                    var tail = value(call.getArguments().getLast(), read);
                    if (tail == Held.LIST || tail == Held.PAIRS) yield tail;
                    if (value(call.getArguments().getFirst(), read) == Held.PAIR) yield Held.PAIRS;
                    yield tail == Held.SAME ? tail : Held.LIST;
                }
                case "mkNilData", "unListData", "constrFields" -> Held.LIST;
                case "mkNilPairData", "unMapData" -> Held.PAIRS;
                case "mkPairData" -> Held.PAIR;
                default -> Held.DATA;
            };
        }

        /** What a method of a source returns, on every path it has. */
        private Held helper(ExecutableElement method, Set<Element> read) {
            MethodTree declaration = facts.declaration(method);
            if (declaration == null || declaration.getBody() == null) return Held.DATA;
            if (!read.add(method)) return Held.SAME;
            var held = Held.SAME;
            for (var result : LoopRules.returns(declaration)) {
                if (result.getExpression() != null) held = held.and(value(result.getExpression(), read));
            }
            return held;
        }

        /** Whether the element a cursor's head goes to is read as a pair: {@code fstPair} of it, or of a local that holds it. */
        private boolean headIsReadAsPair(Element cursor) {
            var heads = new HashSet<Element>();
            for (var call : calls) {
                if (isBuiltinCall(call, "headList") && isRead(call.getArguments().getFirst(), cursor)
                        && facts.parent(call) instanceof VariableTree local) {
                    heads.add(facts.element(local));
                }
            }
            for (var call : calls) {
                if (!isBuiltinCall(call, "fstPair") && !isBuiltinCall(call, "sndPair")) continue;
                var pair = call.getArguments().getFirst();
                if (pair instanceof IdentifierTree name && heads.contains(facts.element(name))) return true;
                if (pair instanceof MethodInvocationTree head && isBuiltinCall(head, "headList") && isRead(head.getArguments().getFirst(), cursor)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isRead(ExpressionTree tree, Element variable) {
            return tree instanceof IdentifierTree name && variable.equals(facts.elementOrNull(name));
        }
    }

    private boolean isBuiltin(ExecutableElement method) {
        return method.getEnclosingElement() instanceof TypeElement owner && facts.identity(owner).equals(JavacNames.BUILTINS);
    }

    private boolean isBuiltinCall(MethodInvocationTree call, String name) {
        return facts.element(call.getMethodSelect()) instanceof ExecutableElement method && isBuiltin(method)
                && method.getSimpleName().contentEquals(name);
    }
}
