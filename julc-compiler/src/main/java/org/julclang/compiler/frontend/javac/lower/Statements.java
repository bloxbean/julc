package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import java.util.ArrayList;

/**
 * Method bodies, in the reference's continuation style. A method is a lambda over its parameters around its body. A
 * {@code return} evaluates its expression and leaves the method, so its operands' order is the expression's.
 */
final class Statements {

    private final JavacFacts facts;
    private final Terms terms;
    private final Lowering lowering;

    Statements(JavacFacts facts, Terms terms, Lowering lowering) {
        this.facts = facts;
        this.terms = terms;
        this.lowering = lowering;
    }

    PirTerm method(MethodTree tree, Scope enclosing, boolean guardBoolean) {
        if (tree.getBody() == null) throw new CompilerException("Method must have a body: " + tree.getName());
        var element = (ExecutableElement) facts.element(tree);
        var returnType = element.getReturnType();
        var scope = enclosing;
        var binders = new ArrayList<Binder>();
        for (var parameter : tree.getParameters()) {
            var parameterElement = facts.element(parameter);
            scope = scope.bind(parameterElement, facts.pirType(parameterElement.asType(), parameter));
            binders.add(scope.lookup(parameterElement));
        }
        boolean isVoid = returnType.getKind() == TypeKind.VOID;
        var exit = new Frame.Exit(isVoid ? null : facts.pirType(returnType, tree.getReturnType()),
                guardBoolean && returnType.getKind() == TypeKind.BOOLEAN);
        PirTerm result = block(tree.getBody(), new Frame(scope, exit));

        // Wrap body in lambda chain: \p1 -> \p2 -> ... -> body
        for (int i = binders.size() - 1; i >= 0; i--) result = terms.lam(binders.get(i), result);
        return terms.at(tree, result);
    }

    private PirTerm block(BlockTree block, Frame frame) {
        var statements = block.getStatements();
        if (statements.isEmpty()) return new PirTerm.Const(Constant.unit());
        // javac rejects a statement after a return, so a return is the only statement a port of return alone can see.
        if (statements.size() == 1 && statements.getFirst() instanceof ReturnTree ret) return ret(ret, frame);
        throw NotPorted.at(facts, statements.getFirst());
    }

    private PirTerm ret(ReturnTree tree, Frame frame) {
        ExpressionTree expression = tree.getExpression();
        PirTerm result;
        if (expression == null) {
            result = new PirTerm.Const(Constant.unit());
        } else {
            result = lowering.expr(expression, frame);
            if (frame.exit().returnType() != null) requireNativeBoundary(expression, frame.exit().returnType());
        }
        terms.at(tree, result);
        return guarded(tree, result, frame);
    }

    /**
     * ADR-047: a native value cannot leave through a differently typed return, nor a byte string or Data through a
     * natively typed one. javac admits this only through a raw type.
     */
    private void requireNativeBoundary(ExpressionTree expression, PirType expected) {
        PirType actual = facts.pirType(facts.type(expression), expression);
        var registry = facts.registry();
        if ((registry.containsNativeOpaque(expected) || registry.containsNativeOpaque(actual)) && !expected.equals(actual)) {
            throw CompilerTypeDiagnostics.nativeTypeMismatch("Return value", actual, expected, facts.location(expression));
        }
    }

    /** With a source map, an entrypoint's boolean {@code return} fails here rather than in the wrapper. */
    private PirTerm guarded(ReturnTree tree, PirTerm result, Frame frame) {
        if (!frame.exit().guardBoolean()) return result;
        var failure = terms.at(tree, new PirTerm.Error(new PirType.BoolType()));
        return terms.at(tree, new PirTerm.IfThenElse(result, new PirTerm.Const(Constant.bool(true)), failure));
    }
}
