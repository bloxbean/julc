package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.subset.FieldUpdates;
import org.julclang.compiler.frontend.javac.subset.LoopRules;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The statements of a loop with accumulators. The reference has four generators, by one accumulator or several and by
 * {@code break} or none. They differ in what {@link Carried} holds, and in the four things {@link Shape} and its uses
 * name, so this is one walk, and each difference below is the reference's.
 * <p>
 * A body is a term that ends in what the next iteration gets. A body without {@code break} is a fold's: its value is the new
 * accumulators, and {@link Loops} makes the recursive call around it. A body with {@code break} makes that call itself:
 * {@code next} takes the value to the next iteration, and a {@code break} is the value alone, which ends the loop.
 * <p>
 * <b>Evaluation order.</b> Statements run in source order, each bound by a strict {@code let} around what follows, so an
 * update is visible to what follows it and to nothing before. An {@code if} evaluates its condition and one branch. In a
 * fold the branch's value is bound for what follows the {@code if}. With a {@code break} in a branch, a path that does not
 * break runs what follows the {@code if} and only then goes to the next iteration (#254, #255): at most one path reaches
 * it, so it stands in place, or several do, and it is bound once as a join every such path calls, with the accumulators as
 * its argument, so it runs once and is not copied. A path that breaks runs none of it.
 * <p>
 * A {@code break} ends the loop with the accumulators as they stand at it. An {@code if} around a {@code break} joins only
 * accumulators, which is why {@link LoopRules} rejects an update of a body's local across one.
 * <p>
 * A bare block's statements run in sequence with what follows it, so they are spliced into the list. Its locals end with
 * it because javac bound their names, and no scope is needed to keep what follows from reading them. A local that shadows
 * a field takes another name from {@link Scope}, so nothing it covers is captured.
 */
final class LoopBody {

    /**
     * What a walk ends in: {@code next} is null in a fold, whose value is what it carries.
     *
     * @param next what a path that does not break does with the value
     */
    private record Shape(Carried carried, UnaryOperator<PirTerm> next) {
        boolean breaks() {
            return next != null;
        }

        /** The same accumulators with no {@code break}: a branch that holds none, under an {@code if} that does. */
        Shape fold() {
            return new Shape(carried, null);
        }

        PirTerm end(PirTerm value) {
            return breaks() ? next.apply(value) : value;
        }
    }

    private final JavacFacts facts;
    private final Terms terms;
    private final Lowering lowering;
    private final Statements statements;
    private final Loops loops;
    private final FieldUpdates fieldUpdates;
    /** Numbers the joins, so a nested one never shadows the one around it. */
    private int joins;

    LoopBody(JavacFacts facts, Terms terms, Lowering lowering, Statements statements, Loops loops, FieldUpdates fieldUpdates) {
        this.facts = facts;
        this.terms = terms;
        this.lowering = lowering;
        this.statements = statements;
        this.loops = loops;
        this.fieldUpdates = fieldUpdates;
    }

    /** A body with no {@code break}: its value is {@code carried} as the body leaves it. */
    PirTerm fold(StatementTree body, Carried carried, Frame frame) {
        return branch(body, frame, new Shape(carried, null));
    }

    /** A body with a {@code break}: {@code next} goes on with a value, and a {@code break} stops at one. */
    PirTerm breaking(StatementTree body, Carried carried, UnaryOperator<PirTerm> next, Frame frame) {
        return branch(body, frame, new Shape(carried, next));
    }

    private PirTerm branch(StatementTree branch, Frame frame, Shape shape) {
        return sequence(branch instanceof BlockTree block ? block.getStatements() : List.of(branch), 0, frame, shape);
    }

    private PirTerm sequence(List<? extends StatementTree> list, int index, Frame frame, Shape shape) {
        if (index >= list.size()) return shape.end(shape.carried().value());
        return switch (list.get(index)) {
            case BlockTree block -> sequence(spliced(list, index, block), 0, frame, shape);
            case BreakTree ignored when shape.breaks() -> shape.carried().value();
            case ExpressionStatementTree statement -> expression(statement.getExpression(), list, index, frame, shape);
            case VariableTree local -> statements.local(local, frame, inner -> sequence(list, index + 1, inner, shape));
            case IfTree branch -> shape.breaks() ? breakingIf(branch, list, index, frame, shape) : foldIf(branch, list, index, frame, shape);
            case WhileLoopTree loop -> nested(loop, "A nested while loop", list, index, frame, shape);
            case EnhancedForLoopTree loop -> nested(loop, "A nested for-each loop", list, index, frame, shape);
            case StatementTree other -> throw unsupported(other, shape);
        };
    }

    /** The statements of a bare block, then what follows it. */
    private static List<StatementTree> spliced(List<? extends StatementTree> list, int index, BlockTree block) {
        var result = new ArrayList<StatementTree>(block.getStatements());
        result.addAll(list.subList(index + 1, list.size()));
        return result;
    }

    private CompilerException unsupported(StatementTree statement, Shape shape) {
        String name = Statements.statementName(statement);
        if (!shape.carried().isSingle()) {
            return shape.breaks()
                    ? facts.locatedRejection("Unsupported in multi-acc break-aware body: " + name,
                            "Inside multi-accumulator loops with break, only variable declarations, assignments, if/else, and break are supported.", statement)
                    : facts.locatedRejection("Unsupported in multi-acc loop body: " + name,
                            "Inside multi-accumulator loops, only variable declarations, assignments, and if/else are supported.", statement);
        }
        return shape.breaks()
                ? facts.locatedRejection("Unsupported statement in break-aware loop body: " + name,
                        "Inside loops with break, only variable declarations, assignments, if/else, and break are supported.", statement)
                : statements.unsupportedAsValue(statement);
    }

    /** An assignment to a name is bound for what follows. Anything else runs for its effect, and {@code Lowering} rejects any assignment in it. */
    private PirTerm expression(ExpressionTree expression, List<? extends StatementTree> list, int index, Frame frame, Shape shape) {
        var target = LoopRules.updated(expression);
        if (target == null) {
            var effect = lowering.expr(expression, frame);
            return new PirTerm.Let("#_", effect, sequence(list, index + 1, frame, shape));
        }
        var element = facts.element(target);
        var binder = frame.scope().lookup(element);
        var value = assigned(expression, target, binder, frame);
        // A value that goes straight to a break: with one accumulator that value is what the loop leaves.
        boolean endsAtBreak = shape.breaks() && shape.carried().holds(element)
                && index + 1 < list.size() && list.get(index + 1) instanceof BreakTree;
        if (endsAtBreak && shape.carried().isSingle()) return value;
        var rest = endsAtBreak ? shape.carried().value() : sequence(list, index + 1, frame, shape);
        return terms.let(binder, value, rest);
    }

    /** What an assignment stores. A name with no binder is a field no binder stands for, which is not a variable here. */
    private PirTerm assigned(ExpressionTree assignment, IdentifierTree target, Scope.Binder binder, Frame frame) {
        if (binder == null) {
            throw facts.locatedRejection("Assignment to undeclared variable '" + target.getName() + "'",
                    "Declare the variable before assigning it: before the loop for an accumulator, in the loop body for a local.", assignment);
        }
        var element = facts.element(target);
        if (element.getKind() == ElementKind.FIELD) fieldUpdates.require((VariableElement) element, assignment);
        return lowering.assigned(assignment, binder, frame);
    }

    private PirTerm foldIf(IfTree tree, List<? extends StatementTree> list, int index, Frame frame, Shape shape) {
        var condition = lowering.expr(Ifs.condition(tree), frame);
        var result = branches(condition, tree, frame, shape);
        if (index + 1 >= list.size()) return result;
        return shape.carried().rebound(result, sequence(list, index + 1, frame, shape));
    }

    /** The {@code if} as a value: each branch's, and with no {@code else} the accumulators as they stand. */
    private PirTerm branches(PirTerm condition, IfTree tree, Frame frame, Shape shape) {
        var thenTerm = branch(tree.getThenStatement(), frame, shape);
        var elseTerm = tree.getElseStatement() != null ? branch(tree.getElseStatement(), frame, shape) : shape.carried().value();
        return new PirTerm.IfThenElse(condition, thenTerm, elseTerm);
    }

    /** See the class's comment. The reference ends an {@code if} with no {@code break} by one accumulator's value or by each branch going on. */
    private PirTerm breakingIf(IfTree tree, List<? extends StatementTree> list, int index, Frame frame, Shape shape) {
        var condition = lowering.expr(Ifs.condition(tree), frame);
        var thenStatement = tree.getThenStatement();
        var elseStatement = tree.getElseStatement();
        boolean breaks = LoopRules.firstExit(thenStatement, BreakTree.class) != null
                || elseStatement != null && LoopRules.firstExit(elseStatement, BreakTree.class) != null;
        boolean hasRest = index + 1 < list.size();
        var carried = shape.carried();

        if (!breaks && (hasRest || carried.isSingle())) {
            var result = branches(condition, tree, frame, shape.fold());
            return hasRest ? carried.rebound(result, sequence(list, index + 1, frame, shape)) : shape.next().apply(result);
        }
        if (!hasRest || elseStatement == null && !Exits.canCompleteNormally(thenStatement)) {
            // At most one path reaches what follows, so it stands in place.
            var thenTerm = branch(thenStatement, frame, shape);
            var elseTerm = elseStatement != null ? branch(elseStatement, frame, shape) : sequence(list, index + 1, frame, shape);
            return new PirTerm.IfThenElse(condition, thenTerm, elseTerm);
        }
        return joined(condition, tree, sequence(list, index + 1, frame, shape), frame, shape);
    }

    /** {@code let join = \value -> rest in if ...}: each path that does not break calls it. */
    private PirTerm joined(PirTerm condition, IfTree tree, PirTerm rest, Frame frame, Shape shape) {
        var carried = shape.carried();
        var type = carried.type();
        var join = terms.generated("#join" + ++joins, new PirType.FunType(type, type));
        var toJoin = new Shape(carried, value -> new PirTerm.App(join, value));
        var thenTerm = branch(tree.getThenStatement(), frame, toJoin);
        var elseTerm = tree.getElseStatement() != null ? branch(tree.getElseStatement(), frame, toJoin)
                : toJoin.end(carried.value());
        return terms.generatedLet(join.name(), joinFunction(carried, rest), new PirTerm.IfThenElse(condition, thenTerm, elseTerm));
    }

    private PirTerm joinFunction(Carried carried, PirTerm rest) {
        if (carried.isSingle()) return terms.lam(carried.only(), rest);
        var tuple = terms.generated(PirHelpers.hygienicName("#join_t", PirHelpers.freeVariables(rest)), Carried.TUPLE);
        return terms.generatedLam(tuple.name(), Carried.TUPLE, carried.unpacked(tuple, rest));
    }

    /**
     * A nested loop lowers on its own, then what it leaves is bound for what follows it. With a {@code break}, the reference
     * nests a loop only under several accumulators.
     */
    private PirTerm nested(StatementTree loop, String name, List<? extends StatementTree> list, int index, Frame frame, Shape shape) {
        if (shape.breaks() && shape.carried().isSingle()) {
            throw CompilerTypeDiagnostics.breakLoopStatementUnsupported(name, facts.location(loop));
        }
        var inner = loops.accumulators(loop, frame);
        var result = loops.alone(loop, frame);
        var rest = sequence(list, index + 1, frame, shape);
        if (inner.isEmpty()) return terms.generatedLet(PirHelpers.hygienicName("#_nested", PirHelpers.freeVariables(rest)), result, rest);
        return loops.carried(inner, loop, frame).rebound(result, rest);
    }
}
