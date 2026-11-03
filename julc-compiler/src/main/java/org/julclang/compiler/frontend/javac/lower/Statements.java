package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.YieldTree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.frontend.javac.lower.Scope.Binder;
import org.julclang.compiler.frontend.javac.subset.FieldUpdates;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/**
 * Method bodies, in the reference's continuation style: a statement list is one term, and each statement holds the ones
 * after it. A method is a lambda over its parameters around its body.
 * <p>
 * <b>Evaluation order.</b> Statements run in source order. A local's initializer and an expression statement are bound by
 * a strict {@code let}, so each runs before what follows. A {@code return} evaluates its expression and leaves the method,
 * so nothing after it runs, and its operands' order is the expression's. {@link Ifs} states the order of an {@code if}.
 * <p>
 * A {@code cont} is what runs when a list falls off its end: the statements after an enclosing {@code if}. It is a plain
 * term, built once and reused by reference; null means the list's end is the end of its value, which is unit.
 * <p>
 * A local's binder is chosen by {@link Scope} while the {@code let} is built, and its scope is the {@link Frame} passed to
 * what follows it, so a local ends where its block does, and a name binds as javac bound it. A rejection here is one the
 * reference makes while generating, kept in that order (I2a); what it rejects before generating is {@code SubsetScan}'s.
 */
final class Statements {

    private final JavacFacts facts;
    private final Terms terms;
    private final Typing typing;
    private final Lowering lowering;
    private final CallTargets targets;
    private final Ifs ifs;
    private final Loops loops;

    private static final String SUPPORTED_STATEMENTS =
            "Only variable declarations, if/else, for-each, while, return, and expression statements are supported on-chain.";

    Statements(JavacFacts facts, Terms terms, Typing typing, Lowering lowering, CallTargets targets, TypeTests typeTests,
               CompilationContext context, FieldUpdates fieldUpdates) {
        this.facts = facts;
        this.terms = terms;
        this.typing = typing;
        this.lowering = lowering;
        this.targets = targets;
        this.ifs = new Ifs(facts, terms, this, typeTests, lowering, new Accumulators(facts), typing);
        this.loops = new Loops(facts, terms, targets, lowering, this, context, fieldUpdates);
    }

    void methodDeclared(String binder) {
        loops.methodDeclared(binder);
    }

    static PirTerm unit() {
        return new PirTerm.Const(Constant.unit());
    }

    PirTerm method(MethodTree tree, Scope enclosing, boolean guardBoolean) {
        if (tree.getBody() == null) throw new CompilerException("Method must have a body: " + tree.getName());
        var element = (ExecutableElement) facts.element(tree);
        var returnType = element.getReturnType();
        boolean isVoid = returnType.getKind() == TypeKind.VOID;
        if (!isVoid && !Exits.allPathsReturn(tree.getBody().getStatements())) {
            throw facts.rejection("Method '" + tree.getName() + "' may not return a value on all execution paths",
                    "Ensure all code paths end with a 'return' statement.", tree);
        }
        var scope = enclosing;
        var binders = new ArrayList<Binder>();
        for (var parameter : tree.getParameters()) {
            var parameterElement = facts.element(parameter);
            scope = scope.bind(parameterElement, facts.parameterType(parameter));
            binders.add(scope.lookup(parameterElement));
        }
        var exit = new Frame.Exit(isVoid ? null : facts.pirType(returnType, tree.getReturnType()),
                guardBoolean && returnType.getKind() == TypeKind.BOOLEAN);
        PirTerm result = block(tree.getBody(), Frame.body(scope, exit));

        // Wrap body in lambda chain: \p1 -> \p2 -> ... -> body
        for (int i = binders.size() - 1; i >= 0; i--) {
            var binder = binders.get(i);
            result = terms.lam(binder, result);
            terms.declared(binder.element(), result, binder.name(), binder.type());
        }
        return terms.at(tree, result);
    }

    /** A block as a term: its value is what its statements leave, and unit if it is empty. */
    PirTerm block(BlockTree block, Frame frame) {
        var statements = block.getStatements();
        return statements.isEmpty() ? unit() : sequence(statements, 0, frame, null);
    }

    /** The statements from {@code index}, then {@code cont}. */
    PirTerm sequence(List<? extends StatementTree> statements, int index, Frame frame, PirTerm cont) {
        if (index >= statements.size()) return cont == null ? unit() : cont;
        return switch (statements.get(index)) {
            // javac rejects a statement after a return.
            case ReturnTree tree -> ret(tree, frame);
            // A yield is a switch arm's value, and leaves the arm as a return leaves the method.
            case YieldTree tree -> lowering.expr(tree.getValue(), frame);
            case VariableTree tree -> local(tree, frame, inner -> sequence(statements, index + 1, inner, cont));
            case ExpressionStatementTree tree -> {
                var effect = lowering.expr(tree.getExpression(), frame);
                yield new PirTerm.Let("#_", effect, sequence(statements, index + 1, frame, cont));
            }
            case IfTree tree -> ifs.inSequence(tree, statements, index, frame, cont);
            case EnhancedForLoopTree tree -> loops.loop(tree, statements, index, frame, cont);
            case WhileLoopTree tree -> loops.loop(tree, statements, index, frame, cont);
            case LabeledStatementTree tree -> throw label(tree);
            case StatementTree tree -> throw unsupported(tree);
        };
    }

    /** A branch that falls through to {@code cont}. A block's locals end with it, since {@code frame} is the branch's. */
    PirTerm branch(StatementTree branch, Frame frame, PirTerm cont) {
        return sequence(branch instanceof BlockTree block ? block.getStatements() : List.of(branch), 0, frame, cont);
    }

    /** A statement that holds no {@code return} as a value: a branch of an {@code if}, or the body of a loop with no accumulator. */
    PirTerm single(StatementTree statement, Frame frame) {
        return switch (statement) {
            case BlockTree tree -> block(tree, frame);
            case ExpressionStatementTree tree -> lowering.expr(tree.getExpression(), frame);
            case IfTree tree -> ifs.nested(tree, frame);
            case EnhancedForLoopTree tree -> loops.alone(tree, frame);
            case WhileLoopTree tree -> loops.alone(tree, frame);
            case LabeledStatementTree tree -> throw label(tree);
            default -> throw unsupportedAsValue(statement);
        };
    }

    /** {@code JULC0066}: a label is outside the subset, wherever its statement is. */
    private CompilerException label(LabeledStatementTree tree) {
        return CompilerTypeDiagnostics.labelUnsupported(tree.getLabel() + ":", facts.location(tree));
    }

    /** What the reference has no statement for: a bare block, in a list, is one of these. Each slice ports its own. */
    private CompilerException unsupported(StatementTree tree) {
        return switch (tree.getKind()) {
            case BLOCK, EMPTY_STATEMENT, SWITCH -> facts.rejection("Unsupported statement: " + statementName(tree), SUPPORTED_STATEMENTS, tree);
            default -> NotPorted.at(facts, tree);
        };
    }

    /** A statement with no value: where the reference throws at once, with what it knows in its text. */
    CompilerException unsupportedAsValue(StatementTree tree) {
        return switch (tree.getKind()) {
            case EMPTY_STATEMENT, SWITCH -> facts.locatedRejection("Unsupported statement: " + statementName(tree), SUPPORTED_STATEMENTS, tree);
            default -> NotPorted.at(facts, tree);
        };
    }

    /** The reference's name of a statement it has no lowering for. */
    static String statementName(StatementTree tree) {
        return switch (tree.getKind()) {
            case BLOCK -> "BlockStmt";
            case SWITCH -> "SwitchStmt";
            default -> "EmptyStmt";
        };
    }

    /** {@code let x = value in ...}. The type is what the source wrote; for {@code var}, javac's type of the value. */
    PirTerm local(VariableTree tree, Frame frame, Function<Frame, PirTerm> rest) {
        String name = tree.getName().toString();
        var initializer = tree.getInitializer();
        if (initializer == null) {
            throw new CompilerException("Variable must be initialized: " + name
                    + ". Hint: On-chain variables need initial values, e.g. var " + name + " = BigInteger.ZERO;");
        }
        var value = lowering.expr(initializer, frame);
        var element = facts.element(tree);
        Scope scope;
        if (facts.isGenerated(tree.getType())) {
            var seen = varType(initializer, value);
            scope = frame.scope().bind(element, Typing.holding(held(initializer, value, seen), seen), seen);
        } else {
            var type = facts.pirType(element.asType(), tree.getType());
            typing.requireNativeBoundary("Variable '" + name + "' initializer", typing.of(initializer, value), type, initializer);
            scope = frame.scope().bind(element, type);
        }
        var binder = scope.lookup(element);
        var binding = terms.let(binder, value, rest.apply(frame.in(scope)));
        terms.declared(element, binding, binder.name(), binder.type());
        return binding;
    }

    /**
     * The binder's type of a {@code var}: what the reference gives it ({@link CallTargets#seen}), so a read of a member stays
     * what it is on the initializer itself. Elements of different types have no one type for an array's reads to decode, and
     * that is a rejection.
     */
    private PirType varType(ExpressionTree initializer, PirTerm value) {
        var elements = typing.arrayLiteralElementTypes(value);
        if (elements.isPresent() && new LinkedHashSet<>(elements.get()).size() > 1) {
            throw CompilerTypeDiagnostics.arrayLiteralElementTypesDiffer(elements.get(), facts.location(initializer));
        }
        return targets.seen(initializer, value);
    }

    /** What a {@code var} holds where the reference sees Data: javac's type of its initializer, if it has one here. */
    private PirType held(ExpressionTree initializer, PirTerm value, PirType seen) {
        try {
            return typing.of(initializer, value);
        } catch (NotPorted e) {
            return seen;
        }
    }

    private PirTerm ret(ReturnTree tree, Frame frame) {
        ExpressionTree expression = tree.getExpression();
        PirTerm result;
        if (expression == null) {
            result = unit();
        } else {
            result = lowering.expr(expression, frame);
            if (frame.exit().returnType() != null) {
                typing.requireNativeBoundary("Return value", typing.of(expression, result), frame.exit().returnType(), expression);
            }
        }
        terms.at(tree, result);
        return guarded(tree, result, frame);
    }

    /** With a source map, an entrypoint's boolean {@code return} fails here rather than in the wrapper. */
    private PirTerm guarded(ReturnTree tree, PirTerm result, Frame frame) {
        if (!frame.exit().guardBoolean()) return result;
        var failure = terms.at(tree, new PirTerm.Error(new PirType.BoolType()));
        return terms.at(tree, new PirTerm.IfThenElse(result, new PirTerm.Const(Constant.bool(true)), failure));
    }
}
