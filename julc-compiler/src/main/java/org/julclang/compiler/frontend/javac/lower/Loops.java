package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BreakTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WhileLoopTree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.desugar.LoopDesugarer;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.subset.FieldUpdates;
import org.julclang.compiler.frontend.javac.subset.LoopRules;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import org.julclang.vm.ProtocolCapability;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
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
    private final CallTargets targets;
    private final Lowering lowering;
    private final Statements statements;
    private final LoopRules rules;
    private final FieldUpdates fieldUpdates;
    private final Accumulators accumulators;
    private final AccumulatorTypes accumulatorTypes;
    private final LoopDesugarer desugarer;
    private final LoopBody body;
    /** The binders of the class's static methods, which to the reference are variables that a loop rebinds. */
    private final Set<String> methods = new HashSet<>();

    Loops(JavacFacts facts, Terms terms, CallTargets targets, Lowering lowering, Statements statements,
          CompilationContext context, FieldUpdates fieldUpdates) {
        this.facts = facts;
        this.terms = terms;
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
        this.body = new LoopBody(facts, terms, lowering, statements, this);
    }

    void methodDeclared(String binder) {
        methods.add(binder);
    }

    /** Another class's methods follow: the reference's symbol table is per class. */
    void classBegins() {
        methods.clear();
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

    /** {@code elements} as {@code frame} binds them. */
    Carried carried(List<Element> elements, Tree loop, Frame frame) {
        return new Carried(facts, terms, elements.stream().map(frame.scope()::lookup).toList(), loop);
    }

    // --- For-each ---

    private PirTerm forEach(EnhancedForLoopTree tree, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        rules.check(tree);
        boolean breaks = LoopRules.firstExit(tree.getStatement(), BreakTree.class) != null;
        if (breaks && accumulators.of(tree, frame).isEmpty()) rules.requireNoUpdateOfTheIterable(tree);
        var iterable = lowering.expr(tree.getExpression(), frame);
        requireList(tree);
        var held = held(tree);
        var variable = facts.element(tree.getVariable());
        var seen = seen(tree, iterable);
        var scope = frame.scope().bind(variable, Typing.holding(held, seen), seen);
        var item = scope.lookup(variable);
        var elements = accumulators(tree, frame);

        if (elements.isEmpty()) {
            rules.requireNoReassignment(tree);
            if (breaks) rules.requireNoBreak(tree, tree.getStatement());
            var bodyTerm = statements.single(tree.getStatement(), frame.in(scope));
            var result = (PirTerm.LetRec) desugarer.desugarForEach(iterable, item.name(), NO_ACCUMULATOR, Statements.unit(),
                    new PirType.UnitType(), bodyTerm, held);
            terms.loop(result, null, item);
            if (index + 1 >= following.size() && cont == null) return result;
            var rest = statements.sequence(following, index + 1, frame, cont);
            return terms.generatedLet(PirHelpers.hygienicName("#_forEach", PirHelpers.freeVariables(rest)), result, rest);
        }

        var typed = travelling(elements, tree, List.of(), frame);
        var carried = carried(elements, tree, typed);
        var inner = frame.in(scope).updating();
        boolean single = carried.isSingle();
        String accumulator = single ? carried.only().name() : TUPLE;
        var type = carried.type();
        var initial = carried.value();
        PirTerm result;
        if (breaks) {
            result = desugarer.desugarForEachWithBreak(iterable, item.name(), accumulator, initial, type, (next, value) -> {
                var bodyTerm = body.breaking(tree.getStatement(), carried, next::apply, inner);
                return single ? bodyTerm : carried.unpacked(value, bodyTerm);
            }, held);
        } else {
            var bodyTerm = body.fold(tree.getStatement(), carried, inner);
            if (!single) bodyTerm = carried.unpacked(terms.generated(TUPLE, Carried.TUPLE), bodyTerm);
            result = desugarer.desugarForEach(iterable, item.name(), accumulator, initial, type, bodyTerm, held);
        }
        terms.loop((PirTerm.LetRec) result, single ? carried.only() : null, item);
        return after(result, carried, following, index, typed, cont);
    }

    /** {@code JulcList} and {@code java.util.List} are lists on-chain. An array is not, and the reference walks its bytes as one, which fails. */
    private void requireList(EnhancedForLoopTree tree) {
        // Arrays are outside the subset but a byte[] literal, and the reference walks a byte[]'s bytes as a list, which fails.
        if (facts.type(tree.getExpression()).getKind() == TypeKind.ARRAY) throw facts.rejection(DiagnosticCodes.ARRAY_UNSUPPORTED, tree.getExpression());
    }

    /** What the variable holds, which is what an element decodes by: the type javac gave it, or the element's where it is wider. */
    private PirType held(EnhancedForLoopTree tree) {
        var variable = tree.getVariable();
        var type = facts.element(variable).asType();
        PirType held;
        try {
            held = facts.registry().resolveNamed(facts.isInferred(variable) ? facts.valueType(type, variable) : facts.pirType(type, variable.getType()));
        } catch (CompilerException e) {
            // A wider variable, Object, Number or a box, holds the element's value: what the iterable's element is (ADR-068 M5).
            if (!(facts.type(tree.getExpression()) instanceof DeclaredType iterable)) {
                throw facts.rejection(DiagnosticCodes.TYPE_RESOLUTION_FAILED, variable,
                        "a for-each variable of type " + ArrayIdentityEquality.text(type));
            }
            var arguments = iterable.getTypeArguments();
            held = arguments.isEmpty() ? new PirType.DataType()
                    : facts.registry().resolveNamed(facts.valueType(arguments.getFirst(), tree.getExpression()));
        }
        try {
            terms.requireDecodes(held);
        } catch (CompilerException e) {
            throw facts.located(e, tree.getExpression());
        }
        return held;
    }

    /**
     * What the variable reads as to the reference, whatever is written or javac knows (I2d): the element of the iterable as
     * it sees it. It reads a name, or a call or chain of calls rooted at one ({@link #isRooted}), and of any other form the
     * element is Data: {@code (xs)}, a conditional, a {@code switch}, and a call on any of those.
     */
    private PirType seen(EnhancedForLoopTree tree, PirTerm iterable) {
        var expression = tree.getExpression();
        return isRooted(expression) && targets.seen(expression, iterable) instanceof PirType.ListType list ? list.elemType() : new PirType.DataType();
    }

    /** The reference's walk of a call's receiver: a name, or a call whose receiver is one of these. */
    private static boolean isRooted(ExpressionTree tree) {
        return switch (tree) {
            case IdentifierTree name -> true;
            case MethodInvocationTree call -> !(call.getMethodSelect() instanceof MemberSelectTree select) || isRooted(select.getExpression());
            default -> false;
        };
    }

    // --- While ---

    private PirTerm whileLoop(WhileLoopTree tree, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        rules.check(tree);
        var condition = ((ParenthesizedTree) tree.getCondition()).getExpression();
        boolean breaks = LoopRules.firstExit(tree.getStatement(), BreakTree.class) != null;
        var elements = accumulators(tree, frame);

        if (elements.isEmpty()) {
            if (breaks) rules.requireNoBreak(tree, tree.getStatement());
            var test = lowering.expr(condition, frame);
            var bodyTerm = statements.single(tree.getStatement(), frame);
            var result = desugarer.desugarWhile(test, bodyTerm);
            if (index + 1 >= following.size() && cont == null) return result;
            var rest = statements.sequence(following, index + 1, frame, cont);
            return terms.generatedLet(PirHelpers.hygienicName("#_while", PirHelpers.freeVariables(rest)), result, rest);
        }

        return elements.size() == 1 ? single(tree, condition, carried(elements, tree, frame), breaks, following, index, frame, cont)
                : several(tree, condition, elements, breaks, following, index, frame, cont);
    }

    private PirTerm single(WhileLoopTree tree, ExpressionTree condition, Carried carried, boolean breaks,
                           List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        var accumulator = carried.only();
        var inner = frame.updating();
        var initial = carried.value();
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

    /**
     * {@code frame} with several accumulators at the types they travel at ({@link AccumulatorTypes}): the tuple holds them so, and
     * what follows the loop reads them so. A body reads them as they were declared, as the reference has them there.
     */
    private Frame travelling(List<Element> elements, StatementTree loop, List<? extends StatementTree> preceding, Frame frame) {
        if (elements.size() < 2) return frame;
        var declared = new ArrayList<PirType>();
        elements.forEach(element -> declared.add(frame.scope().lookup(element).type()));
        var types = accumulatorTypes.travelling(loop, preceding, elements, declared);
        var scope = frame.scope();
        for (int i = 0; i < elements.size(); i++) scope = scope.rebind(elements.get(i), types.get(i));
        return frame.in(scope);
    }

    /**
     * What a nested loop leaves, at the types it travels at for each accumulator {@code rest} reads. One nothing reads keeps
     * its declared type, so a term that never decodes it stands as in the reference.
     */
    Carried nested(List<Element> elements, StatementTree loop, Frame frame, PirTerm rest) {
        var typed = travelling(elements, loop, List.of(), frame);
        var free = PirHelpers.freeVariables(rest);
        return new Carried(facts, terms, elements.stream().map(element -> {
            var binder = frame.scope().lookup(element);
            return free.contains(binder.name()) ? typed.scope().lookup(element) : binder;
        }).toList(), loop);
    }

    /** A {@code while} of several accumulators: the condition reads them at the types they travel at. */
    private PirTerm several(WhileLoopTree tree, ExpressionTree condition, List<Element> elements, boolean breaks,
                            List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        var typed = travelling(elements, tree, following.subList(0, index), frame);
        var carried = carried(elements, tree, typed);
        var initial = carried.value();
        var test = lowering.expr(condition, typed);
        var testRead = carried.unpacked(terms.generated(TUPLE, Carried.TUPLE), test);
        var inner = frame.updating();
        PirTerm result;
        if (breaks) {
            result = desugarer.desugarWhileWithAccumulatorAndBreak(testRead, TUPLE, initial, Carried.TUPLE,
                    (next, value) -> carried.unpacked(value, body.breaking(tree.getStatement(), carried, next::apply, inner)));
        } else {
            var bodyTerm = carried.unpacked(terms.generated(TUPLE, Carried.TUPLE), body.fold(tree.getStatement(), carried, inner));
            result = desugarer.desugarWhileWithAccumulator(testRead, bodyTerm, TUPLE, initial, Carried.TUPLE);
        }
        return after(result, carried, following, index, typed, cont);
    }

    // --- What follows ---

    /** {@code result} bound for what follows it. As the last thing its list does, it is the list's value. */
    private PirTerm after(PirTerm result, Carried carried, List<? extends StatementTree> following, int index, Frame frame, PirTerm cont) {
        if (index + 1 >= following.size() && cont == null) return result;
        var rest = statements.sequence(following, index + 1, frame, cont);
        return carried.rebound(result, rebindPreLoopVariables(rest, frame, carried));
    }

    /**
     * {@code let x = x in rest} for each variable visible before the loop that {@code rest} mentions, as the reference writes
     * it: this changes no meaning, and the bytes at the first level hold it. A name is a binder's, a {@code switch} arm's or
     * a static method's, and an accumulator's is not one. {@code rest} is read for these by spelling alone, in the reference's
     * order, which is the order of the {@code let}s. A local of {@code rest} that shadows a field has its own name here, where
     * it has the field's on the reference, so that {@code let} is not written: it can only cost bytes.
     */
    private PirTerm rebindPreLoopVariables(PirTerm rest, Frame frame, Carried carried) {
        var binders = frame.scope().binders();
        carried.names().forEach(binders::remove);
        var candidates = new HashSet<>(binders.keySet());
        frame.patterns().addNamesTo(candidates);
        candidates.addAll(methods);
        var reads = new LinkedHashMap<String, PirTerm.Var>();
        PreLoopReads.collect(rest, candidates, reads);
        PirTerm result = rest;
        for (Map.Entry<String, PirTerm.Var> read : reads.entrySet()) {
            var binder = binders.get(read.getKey());
            result = binder != null ? terms.let(binder, terms.var(binder.element(), binder), result) : terms.rebound(read.getValue(), result);
        }
        return result;
    }
}
