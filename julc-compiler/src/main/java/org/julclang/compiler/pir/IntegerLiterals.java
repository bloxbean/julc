package org.julclang.compiler.pir;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;

import java.math.BigInteger;

/**
 * The value of a Java integer literal, as javac gives it (ADR-062 T25, JLS 21 §3.10.1).
 * <p>
 * An {@code int} literal has 32 bits and a {@code long} literal (suffix {@code L}) 64. A decimal literal
 * denotes its magnitude, which must fit the signed range, except that {@code 2147483648} and
 * {@code 9223372036854775808L} may appear only as the operand of unary minus. A hexadecimal, octal or
 * binary literal denotes the two's-complement value of its bits, which must fit the width: so
 * {@code 0xFFFFFFFF} is -1 and {@code 0x80000000} is -2147483648. Underscores between digits are ignored.
 * <p>
 * JuLC's integers are unbounded, so only the literal's value follows the width; arithmetic on it does not
 * (ADR-062 T6).
 */
public final class IntegerLiterals {

    private IntegerLiterals() {
    }

    /** A literal's value, or why javac rejects it. Exactly one of the two is set. */
    public record Reading(BigInteger value, String problem) {

        static Reading of(BigInteger value) {
            return new Reading(value, null);
        }

        static Reading invalid(String problem) {
            return new Reading(null, problem);
        }

        public boolean isValid() {
            return problem == null;
        }
    }

    /** Reads an {@code int} or {@code long} literal node. */
    public static Reading read(Expression literal) {
        return switch (literal) {
            case IntegerLiteralExpr i -> read(i.getValue(), false, isNegated(i));
            case LongLiteralExpr l -> read(l.getValue(), true, isNegated(l));
            default -> throw new IllegalArgumentException("Not an integer literal: " + literal);
        };
    }

    /**
     * Reads a literal.
     *
     * @param text    the literal as written, for example {@code 0xFF_FFL}
     * @param isLong  whether it is a {@code long} literal
     * @param negated whether it is the direct operand of unary minus, the only place the decimal
     *                literal of the type's most negative magnitude may appear
     */
    public static Reading read(String text, boolean isLong, boolean negated) {
        String digits = text.replace("_", "");
        if (isLong) digits = digits.substring(0, digits.length() - 1);
        int radix = 10;
        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            radix = 16;
            digits = digits.substring(2);
        } else if (digits.startsWith("0b") || digits.startsWith("0B")) {
            radix = 2;
            digits = digits.substring(2);
        } else if (digits.length() > 1 && digits.startsWith("0")) {
            radix = 8;
            digits = digits.substring(1);
        }
        BigInteger magnitude;
        try {
            magnitude = new BigInteger(digits, radix);
        } catch (NumberFormatException e) {
            // JavaParser's lexer accepts 8 and 9 after a leading 0; javac does not.
            return Reading.invalid(radix == 8 ? "an octal literal has only the digits 0 to 7" : "it is not a valid literal");
        }
        int bits = isLong ? 64 : 32;
        var signBit = BigInteger.ONE.shiftLeft(bits - 1);
        if (radix == 10) {
            if (magnitude.compareTo(signBit) < 0 || magnitude.equals(signBit) && negated) return Reading.of(magnitude);
            return Reading.invalid(magnitude.equals(signBit)
                    ? "this magnitude is allowed only directly after a unary minus"
                    : "it is too large for " + (isLong ? "a long" : "an int"));
        }
        if (magnitude.bitLength() > bits) return Reading.invalid("it is too large for " + (isLong ? "a long" : "an int"));
        return Reading.of(magnitude.testBit(bits - 1) ? magnitude.subtract(BigInteger.ONE.shiftLeft(bits)) : magnitude);
    }

    /** A literal as written, without any comment attached to its node, for diagnostics. */
    public static String text(Expression literal) {
        return switch (literal) {
            case IntegerLiteralExpr i -> i.getValue();
            case LongLiteralExpr l -> l.getValue();
            case UnaryExpr ue when ue.getOperator() == UnaryExpr.Operator.MINUS -> "-" + text(ue.getExpression());
            default -> literal.toString();
        };
    }

    /** Whether an expression is the direct operand of unary minus. */
    public static boolean isNegated(Expression expr) {
        return expr.getParentNode().orElse(null) instanceof UnaryExpr ue && ue.getOperator() == UnaryExpr.Operator.MINUS;
    }

    /**
     * The value of a {@code byte[]} initializer element that is an {@code int} literal, negated or not,
     * as javac assigns it to a {@code byte}: it must be in -128 to 127. {@code null} for any other element.
     */
    public static Reading byteElement(Expression element) {
        Expression literal = element instanceof UnaryExpr ue && ue.getOperator() == UnaryExpr.Operator.MINUS
                ? ue.getExpression() : element;
        if (!(literal instanceof IntegerLiteralExpr)) return null;
        var reading = read(literal);
        if (!reading.isValid()) return reading;
        var value = literal == element ? reading.value() : reading.value().negate();
        if (value.compareTo(BigInteger.valueOf(Byte.MIN_VALUE)) < 0 || value.compareTo(BigInteger.valueOf(Byte.MAX_VALUE)) > 0) {
            return Reading.invalid("a byte[] element must be in -128 to 127");
        }
        return Reading.of(value);
    }
}
