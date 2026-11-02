package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BreakTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WhileLoopTree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.desugar.LoopDesugarer;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.frontend.javac.subset.FieldUpdates;
import org.julclang.compiler.frontend.javac.subset.LoopRules;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import org.julclang.vm.ProtocolCapability;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For-each and {@code while}, the reference's {@code generateForEachStmt} and {@code generateWhileStmt}, as ADR-061 owns them
 * and ADR-068 ports them. A loop is a recursive function ({@link LoopDesugarer}) over what it carries ({@link Carried}):
 * the accumulators, or nothing, and what follows it reads the function's result. {@link LoopBody} lowers the statements
 * of a body with accumulators, and {@link LoopRules} says what a loop may be.
 * <p>
 * <b>Evaluation order.</b> A for-each evaluates its iterable once, then reads the accumulators, then runs the function:
 * the list is tested, its head is decoded and bound to the variable, and the body runs, so each element's body runs after
 * the one before it, and before the rest of the list is looked at. A {@code while} reads the accumulators, then evaluates
 * its condition, then its body, each once per iteration. With several accumulators the condition reads them out of the tuple
 * first. The function's result is bound by a strict {@code let} before what follows the loop, so the loop ends before any
 * of that runs. With no accumulators the same holds, and the loop's value is unit. {@link LoopBody} states the order within
 * a body.
 * <p>
 * <b>Generation order</b>, which decides which of two errors comes first, is the reference's: the rules, then the iterable,
 * then the accumulators and any field among them, then the condition, the body, and what follows.
 */
final class Loops {

    /** The accumulator of a for-each with none: its fold carries unit. */
    private static final String NO_ACCUMULATOR = "#acc__forEach";
    private static final String TUPLE = "#__acc_tuple";

    private final JavacFacts facts;
    private final Terms terms;
    private final Typing typing;
    private final CallTargets targets;
    private final Lowering lowering;
    private final Statements statements;
    private final LoopRules rules;
    private final FieldUpdates fieldUpdates;
    private final Accumulators accumulators;
    private final AccumulatorTypes accumulatorTypes;
    private final LoopDesugarer desugarer;
    private final LoopBody body;
    /** The binders of the methods lowered so far, which to the reference are variables that a loop rebinds. */
    private final Set<String> loweredMethods = new HashSet<>();

    Loops(JavacFacts facts, Terms terms, Typing typing, CallTargets targets, Lowering lowering, Statements statements,
          CompilationContext context, FieldUpdates fieldUpdates) {
        this.facts = facts;
        this.terms = terms;
        this.typing = typing;
        this.targets = targets;
        this.lowering = lowering;
        this.statements = statements;
        this.rules = new LoopRules(facts);
        this.fieldUpdates = fieldUpdates;
        this.accumulators = new Accumulators(facts);
        this.accumulatorTypes = new AccumulatorTypes(facts);
        this.desugarer = new LoopDesugarer(context.target().equals(CompilerTarget.PLUTUS_V3_PV11)
                && context.optimizationLevel().pv11SafeRulesEnabled()
                && context.supports(ProtocolCapability.CASE_ON_BUILTIN_CONSTANTS));
        this.body = new LoopBody(facts, terms, lowering, statements, this, fieldUpdates);
    }

    void methodLowered(String binder) {
        loweredMethods.add(binder);
    }

    /** The loop at {@code index} of {@code following}, which continues with what follows it, then {@code cont}. */
    PirTerm loop(StatementTree loop, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        return switch (loop) {
            case EnhancedForLoopTree tree -> forEach(tree, following, index, frame, cont);
            case WhileLoopTree tree -> whileLoop(tree, following, index, frame, cont);
            default -> throw new IllegalArgumentException("Not a loop: " + loop.getKind());
        };
    }

    /** A loop with nothing after it, in a body or a branch: its term is its value. */
    PirTerm alone(StatementTree loop, Frame frame) {
        return loop(loop, List.of(loop), 0, frame, null);
    }

    /** {@code loop}'s accumulators. A field among them meets {@code JULC0056} here, at the first assignment to it. */
    List<Element> accumulators(StatementTree loop, Frame frame) {
        var found = accumulators.of(loop, frame);
        for (var element : found) {
            if (element.getKind() == ElementKind.FIELD) {
                fieldUpdates.require((VariableElement) element, firstUpdate(loop, element));
            }
        }
        return found;
    }

    private Tree firstUpdate(StatementTree loop, Element field) {
        for (var update : LoopRules.updates(loop)) {
            if (facts.element(LoopRules.updated(update)).equals(field)) return update;
        }
        return loop;
    }

    Carried carried(List<Element> elements, Tree loop) {
        return new Carried(facts, terms, elements, loop);
    }

    // --- For-each ---

    private PirTerm forEach(EnhancedForLoopTree tree, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        rules.check(tree);
        var iterable = lowering.expr(tree.getExpression(), frame);
        requireList(tree);
        var held = held(tree);
        var variable = facts.element(tree.getVariable());
        var scope = frame.scope().bind(variable, seen(tree, iterable));
        var item = scope.lookup(variable);
        boolean breaks = LoopRules.firstExit(tree.getStatement(), BreakTree.class) != null;
        var elements = accumulators(tree, frame);

        if (elements.isEmpty()) {
            rules.requireNoReassignment(tree);
            if (breaks) rules.requireNoBreak(tree, tree.getExpression(), tree.getStatement());
            var bodyTerm = statements.single(tree.getStatement(), frame.in(scope));
            var result = (PirTerm.LetRec) desugarer.desugarForEach(iterable, item.name(), NO_ACCUMULATOR, Statements.unit(),
                    new PirType.UnitType(), bodyTerm, held);
            terms.loop(result, null, item);
            if (index + 1 >= following.size() && cont == null) return result;
            var rest = statements.sequence(following, index + 1, frame, cont);
            return new PirTerm.Let(PirHelpers.hygienicName("#_forEach", PirHelpers.freeVariables(rest)), result, rest);
        }

        var carried = carried(elements, tree);
        var inner = frame.in(scope).updating();
        boolean single = carried.isSingle();
        String accumulator = single ? carried.only(frame).name() : TUPLE;
        var type = carried.type(frame);
        var initial = carried.value(frame);
        PirTerm result;
        if (breaks) {
            result = desugarer.desugarForEachWithBreak(iterable, item.name(), accumulator, initial, type, (next, value) -> {
                var bodyTerm = body.breaking(tree.getStatement(), carried, next::apply, inner);
                return single ? bodyTerm : carried.unpacked(value, inner, bodyTerm);
            }, held);
        } else {
            var bodyTerm = body.fold(tree.getStatement(), carried, inner);
            if (!single) bodyTerm = carried.unpacked(terms.generated(TUPLE, Carried.TUPLE), inner, bodyTerm);
            result = desugarer.desugarForEach(iterable, item.name(), accumulator, initial, type, bodyTerm, held);
        }
        terms.loop((PirTerm.LetRec) result, single ? carried.only(frame) : null, item);
        return after(result, carried, following, index, frame, cont);
    }

    /** {@code JulcList} and {@code java.util.List} are lists on-chain. An array is not, and the reference walks its bytes as one, which fails. */
    private void requireList(EnhancedForLoopTree tree) {
        if (facts.type(tree.getExpression()).getKind() == TypeKind.ARRAY) throw NotPorted.at(facts, tree.getExpression(), "a for-each over an array");
    }

    /** What the variable holds, which is what an element decodes by: the type javac gave it. */
    private PirType held(EnhancedForLoopTree tree) {
        var variable = tree.getVariable();
        var type = facts.element(variable).asType();
        if (!facts.isInferred(variable)) return facts.registry().resolveNamed(facts.pirType(type, variable.getType()));
        try {
            return facts.registry().resolveNamed(facts.valueType(type, variable));
        } catch (CompilerException e) {
            throw NotPorted.at(facts, variable, "a for-each variable of type " + ArrayIdentityEquality.text(type));
        }
    }

    /** What the variable reads as to the reference: the element of the iterable as it sees it, whatever is written or javac knows (I2d). */
    private PirType seen(EnhancedForLoopTree tree, PirTerm iterable) {
        return targets.seen(tree.getExpression(), iterable) instanceof PirType.ListType list ? list.elemType() : new PirType.DataType();
    }

    // --- While ---

    private PirTerm whileLoop(WhileLoopTree tree, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        rules.check(tree);
        var condition = ((ParenthesizedTree) tree.getCondition()).getExpression();
        boolean breaks = LoopRules.firstExit(tree.getStatement(), BreakTree.class) != null;
        var elements = accumulators(tree, frame);

        if (elements.isEmpty()) {
            if (breaks) rules.requireNoBreak(tree, tree.getCondition(), tree.getStatement());
            var test = lowering.expr(condition, frame);
            var bodyTerm = statements.single(tree.getStatement(), frame);
            var result = desugarer.desugarWhile(test, bodyTerm);
            if (index + 1 >= following.size() && cont == null) return result;
            var rest = statements.sequence(following, index + 1, frame, cont);
            return new PirTerm.Let(PirHelpers.hygienicName("#_while", PirHelpers.freeVariables(rest)), result, rest);
        }

        var carried = carried(elements, tree);
        return carried.isSingle() ? single(tree, condition, carried, breaks, following, index, frame, cont)
                : several(tree, condition, carried, elements, breaks, following, index, frame, cont);
    }

    private PirTerm single(WhileLoopTree tree, ExpressionTree condition, Carried carried, boolean breaks,
                           List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        var accumulator = carried.only(frame);
        var inner = frame.updating();
        var initial = carried.value(frame);
        PirTerm result;
        if (breaks) {
            var test = lowering.expr(condition, frame);
            result = desugarer.desugarWhileWithAccumulatorAndBreak(test, accumulator.name(), initial, accumulator.type(),
                    (next, value) -> body.breaking(tree.getStatement(), carried, next::apply, inner));
        } else {
            var test = lowering.expr(condition, inner);
            var bodyTerm = body.fold(tree.getStatement(), carried, inner);
            result = desugarer.desugarWhileWithAccumulator(test, bodyTerm, accumulator.name(), initial, accumulator.type());
        }
        terms.loop((PirTerm.LetRec) result, accumulator, null);
        return after(result, carried, following, index, frame, cont);
    }

    /** Several accumulators, typed for the tuple ({@link AccumulatorTypes}), so what follows the loop reads them at those types. */
    private PirTerm several(WhileLoopTree tree, ExpressionTree condition, Carried carried, List<Element> elements, boolean breaks,
                            List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        var declared = new ArrayList<PirType>();
        elements.forEach(element -> declared.add(frame.scope().lookup(element).type()));
        var types = accumulatorTypes.refined(tree, elements, declared, following.subList(0, index));
        var scope = frame.scope();
        for (int i = 0; i < elements.size(); i++) scope = scope.rebind(elements.get(i), types.get(i));
        var typed = frame.in(scope);
        var initial = carried.value(typed);
        var test = lowering.expr(condition, typed);
        var testRead = carried.unpacked(terms.generated(TUPLE, Carried.TUPLE), typed, test);
        var inner = typed.updating();
        PirTerm result;
        if (breaks) {
            result = desugarer.desugarWhileWithAccumulatorAndBreak(testRead, TUPLE, initial, Carried.TUPLE,
                    (next, value) -> carried.unpacked(value, inner, body.breaking(tree.getStatement(), carried, next::apply, inner)));
        } else {
            var bodyTerm = carried.unpacked(terms.generated(TUPLE, Carried.TUPLE), inner, body.fold(tree.getStatement(), carried, inner));
            result = desugarer.desugarWhileWithAccumulator(testRead, bodyTerm, TUPLE, initial, Carried.TUPLE);
        }
        return after(result, carried, following, index, typed, cont);
    }

    // --- What follows ---

    /** {@code result} bound for what follows it. As the last thing its list does, it is the list's value. */
    private PirTerm after(PirTerm result, Carried carried, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        if (index + 1 >= following.size() && cont == null) return result;
        var rest = statements.sequence(following, index + 1, frame, cont);
        return carried.rebound(result, rebindPreLoopVariables(rest, frame, carried), frame);
    }

    /**
     * {@code let x = x in rest} for each variable visible before the loop that {@code rest} mentions, as the reference writes
     * it, which fixes nothing here and which the bytes at the first level hold. A name is a binder's, a {@code switch} arm's
     * or a lowered method's, an accumulator's is not one of these, and {@code rest} is read for it by spelling alone, in the
     * reference's order, since that order is the order of the {@code let}s.
     */
    private PirTerm rebindPreLoopVariables(PirTerm rest, Frame frame, Carried carried) {
        var candidates = new HashSet<>(frame.scope().names());
        frame.patterns().addNamesTo(candidates);
        candidates.addAll(loweredMethods);
        candidates.removeAll(carried.names(frame));
        var reads = new LinkedHashMap<String, PirTerm.Var>();
        PreLoopReads.collect(rest, candidates, reads);
        PirTerm result = rest;
        for (Map.Entry<String, PirTerm.Var> read : reads.entrySet()) result = terms.rebound(read.getValue(), result);
        return result;
    }
}
