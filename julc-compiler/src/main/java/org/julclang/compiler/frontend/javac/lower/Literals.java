package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import java.math.BigInteger;

/**
 * Literals (ADR-068 I9): the value is javac's, from {@link LiteralTree#getValue()}. Evaluating a literal has no effect, so
 * this family has no evaluation order to state.
 * <p>
 * javac folds a minus sign and a non-zero decimal literal into one literal, so {@code -5}, {@code - 5} and
 * {@code -2147483648} arrive as a {@code LiteralTree} holding the negative value, while {@code -0}, {@code -0x10} and
 * {@code -(5)} stay unary. The reference lowers the first group as it lowers every negation, {@code 0 - 5}, and this
 * keeps that term. A negative value alone does not say which it is: {@code 0xFFFFFFFF} is -1 and was never negated.
 */
final class Literals {

    private final JavacFacts facts;
    private final Terms terms;

    Literals(JavacFacts facts, Terms terms) {
        this.facts = facts;
        this.terms = terms;
    }

    PirTerm literal(LiteralTree tree) {
        return switch (tree.getKind()) {
            case INT_LITERAL, LONG_LITERAL -> integer(tree);
            case BOOLEAN_LITERAL -> new PirTerm.Const(Constant.bool((Boolean) tree.getValue()));
            case STRING_LITERAL -> string(tree);
            // A char has no on-chain representation, as the reference says of its type (JULC0124). Floating point and null
            // are rejected by their own rules before lowering.
            // The scan reads a char variable's type first.
            case CHAR_LITERAL -> throw facts.rejection(DiagnosticCodes.EXPRESSION_UNSUPPORTED, tree, "a char literal");
            case FLOAT_LITERAL, DOUBLE_LITERAL -> throw facts.rejection(DiagnosticCodes.FLOATING_POINT_UNSUPPORTED, tree);
            case NULL_LITERAL -> throw facts.rejection(DiagnosticCodes.NULL_UNSUPPORTED, tree);
            default -> throw facts.rejection(DiagnosticCodes.EXPRESSION_UNSUPPORTED, tree, facts.location(tree).fragment());
        };
    }

    private PirTerm integer(LiteralTree tree) {
        var value = BigInteger.valueOf(((Number) tree.getValue()).longValue());
        if (!isFoldedNegation(tree)) return new PirTerm.Const(Constant.integer(value));
        return terms.at(tree, new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                new PirTerm.Const(Constant.integer(BigInteger.ZERO))), new PirTerm.Const(Constant.integer(value.negate()))));
    }

    /** Whether javac folded a minus sign into this literal: its first character, as javac reads it, is that sign. */
    private boolean isFoldedNegation(LiteralTree tree) {
        return facts.leadingSource(tree, 1).equals("-");
    }

    /** A text block is not a string literal of the subset: the reference does not lower one. */
    private PirTerm string(LiteralTree tree) {
        // JuLC does not lower a text block.
        if (facts.leadingSource(tree, 3).equals("\"\"\"")) throw facts.rejection(DiagnosticCodes.EXPRESSION_UNSUPPORTED, tree, "a text block");
        return new PirTerm.Const(Constant.string((String) tree.getValue()));
    }

    /**
     * The one array of the subset: {@code new byte[]{...}} over integer literals, each negated or not, is a byte string
     * constant. javac has checked that each element fits a {@code byte}.
     */
    PirTerm byteArray(NewArrayTree tree) {
        var type = facts.type(tree);
        if (tree.getType() == null || tree.getInitializers() == null
                || !(type instanceof ArrayType array && array.getComponentType().getKind() == TypeKind.BYTE)) {
            throw facts.rejection(DiagnosticCodes.ARRAY_UNSUPPORTED, tree);
        }
        var elements = tree.getInitializers();
        byte[] bytes = new byte[elements.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = byteElement(elements.get(i));
        return new PirTerm.Const(Constant.byteString(bytes));
    }

    private byte byteElement(ExpressionTree element) {
        if (element instanceof LiteralTree literal && element.getKind() == Tree.Kind.INT_LITERAL) {
            return ((Number) literal.getValue()).byteValue();
        }
        if (element instanceof UnaryTree unary && element.getKind() == Tree.Kind.UNARY_MINUS
                && unary.getExpression() instanceof LiteralTree literal
                && literal.getKind() == Tree.Kind.INT_LITERAL) {
            return (byte) -((Number) literal.getValue()).intValue();
        }
        throw facts.rejection(DiagnosticCodes.ARRAY_UNSUPPORTED, element);
    }
}
