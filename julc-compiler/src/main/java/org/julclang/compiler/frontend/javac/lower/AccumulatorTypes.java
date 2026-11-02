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
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.frontend.javac.SwitchResults;
import org.julclang.compiler.frontend.javac.subset.LoopRules;
import org.julclang.compiler.pir.PirType;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a {@code PlutusData} accumulator holds when several travel in a tuple. Its Java type does not say: a builtin leaves a
 * list or a pair list under it, and packing one as Data fails. So this reads what reaches the variable, from what javac bound:
 * its initializer and every assignment to it in the method, through parentheses, conditionals, a {@code switch}, other
 * variables, and the {@code return}s of a helper. A call is the element javac bound, so {@code tailList} is
 * {@code Builtins.tailList} and nothing else.
 * <p>
 * A cursor nothing else says of is a list, unless what its head is read as, or a map cursor beside it, says pairs. A variable
 * shown to hold both a list and Data has no one type for the tuple, and it stops. This builds no term, so it has no
 * evaluation order to state.
 */
final class AccumulatorTypes {

    private static final PirType MAP = new PirType.MapType(new PirType.DataType(), new PirType.DataType());
    private static final PirType LIST = new PirType.ListType(new PirType.DataType());
    private static final String MAP_DATA = JavacNames.PLUTUS_DATA + ".MapData";

    /** What is known of a value. */
    private enum Held {
        NOTHING, DATA, PAIR, CURSOR, LIST, PAIRS, BOTH;

        boolean isList() {
            return this == CURSOR || this == LIST || this == PAIRS;
        }

        Held and(Held other) {
            if (this == NOTHING || this == other) return other;
            if (other == NOTHING) return this;
            if (isList() && other.isList()) return ordinal() > other.ordinal() ? this : other;
            return BOTH;
        }
    }

    private final JavacFacts facts;

    AccumulatorTypes(JavacFacts facts) {
        this.facts = facts;
    }

    /**
     * @param accumulators the loop's, in order
     * @param types        their declared types, in order
     * @return the types they travel at
     */
    List<PirType> travelling(StatementTree loop, List<Element> accumulators, List<PirType> types) {
        var method = facts.enclosing(loop, Tree.Kind.METHOD);
        var reading = new Reading(method == null ? loop : method);
        var result = new ArrayList<>(types);
        var held = new ArrayList<Held>();
        for (int i = 0; i < accumulators.size(); i++) {
            var what = result.get(i) instanceof PirType.DataType ? reading.variable(accumulators.get(i), new HashSet<>()) : Held.NOTHING;
            if (what == Held.BOTH) throw NotPorted.at(facts, loop, "an accumulator shown to hold both a list and Data");
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

        /** What {@code variable} is shown to hold. {@code read} holds what is being read, so a cursor says nothing of itself. */
        Held variable(Element variable, Set<Element> read) {
            if (!read.add(variable)) return Held.NOTHING;
            var held = isMapData(variable) ? Held.PAIRS : Held.NOTHING;
            if (variable instanceof VariableElement local && facts.initializer(local) != null) {
                held = held.and(value(facts.initializer(local), read));
            }
            for (var assignment : assignments) {
                if (assignment.getVariable() instanceof IdentifierTree target && variable.equals(facts.element(target))) {
                    held = held.and(value(assignment.getExpression(), read));
                }
            }
            if (held.isList() && isReadAsData(variable)) return Held.BOTH;
            return held == Held.CURSOR && headIsReadAsPair(variable) ? Held.PAIRS : held;
        }

        private Held value(ExpressionTree tree, Set<Element> read) {
            return switch (tree) {
                case ParenthesizedTree parenthesized -> value(parenthesized.getExpression(), read);
                case ConditionalExpressionTree conditional ->
                        value(conditional.getTrueExpression(), read).and(value(conditional.getFalseExpression(), read));
                case SwitchExpressionTree selected -> {
                    var held = Held.NOTHING;
                    for (var result : SwitchResults.of(selected)) held = held.and(value(result, read));
                    yield held;
                }
                case IdentifierTree name when facts.elementOrNull(name) instanceof VariableElement variable -> variable(variable, read);
                case MethodInvocationTree call -> call(call, read);
                default -> Held.NOTHING;
            };
        }

        private Held call(MethodInvocationTree call, Set<Element> read) {
            if (!(facts.element(call.getMethodSelect()) instanceof ExecutableElement method)) return Held.NOTHING;
            if (!isBuiltin(method)) return helper(method, read);
            return switch (method.getSimpleName().toString()) {
                case "tailList" -> {
                    var list = value(call.getArguments().getFirst(), read);
                    yield list.isList() ? list : Held.CURSOR;
                }
                case "mkCons" -> value(call.getArguments().getFirst(), read) == Held.PAIR ? Held.PAIRS
                        : value(call.getArguments().getLast(), read) == Held.PAIRS ? Held.PAIRS : Held.LIST;
                case "mkNilData", "unListData", "constrFields" -> Held.LIST;
                case "mkNilPairData", "unMapData" -> Held.PAIRS;
                case "mkPairData" -> Held.PAIR;
                case "iData", "bData", "constrData", "listData", "mapData" -> Held.DATA;
                default -> Held.NOTHING;
            };
        }

        /** What a method of a source returns, on every path it has. */
        private Held helper(ExecutableElement method, Set<Element> read) {
            MethodTree declaration = facts.declaration(method);
            if (declaration == null || declaration.getBody() == null || !read.add(method)) return Held.NOTHING;
            var held = Held.NOTHING;
            for (var result : LoopRules.returns(declaration)) {
                if (result.getExpression() != null) held = held.and(value(result.getExpression(), read));
            }
            return held;
        }

        /** {@code unListData(variable)} and the like take Data, whatever else reaches the variable. */
        private boolean isReadAsData(Element variable) {
            for (var call : calls) {
                if (isDecodeOf(call, variable)) return true;
            }
            return false;
        }

        private boolean isDecodeOf(MethodInvocationTree call, Element variable) {
            return facts.element(call.getMethodSelect()) instanceof ExecutableElement method && isBuiltin(method)
                    && List.of("unListData", "unMapData", "unIData", "unBData", "unConstrData").contains(method.getSimpleName().toString())
                    && isRead(call.getArguments().getFirst(), variable);
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

    private boolean isMapData(Element variable) {
        var type = variable.asType();
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).equals(MAP_DATA);
    }

    private boolean isBuiltin(ExecutableElement method) {
        return method.getEnclosingElement() instanceof TypeElement owner && facts.identity(owner).equals(JavacNames.BUILTINS);
    }

    private boolean isBuiltinCall(MethodInvocationTree call, String name) {
        return facts.element(call.getMethodSelect()) instanceof ExecutableElement method && isBuiltin(method)
                && method.getSimpleName().contentEquals(name);
    }
}
