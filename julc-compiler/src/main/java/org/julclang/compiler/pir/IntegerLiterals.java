package org.julclang.compiler.pir;

import java.math.BigInteger;
import java.util.Optional;

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

    /**
     * The value of a literal, or empty where javac rejects it as too large.
     *
     * @param text    the literal as written, for example {@code 0xFF_FFL}
     * @param isLong  whether it is a {@code long} literal
     * @param negated whether it is the direct operand of unary minus, the only place the decimal
     *                literal of the type's most negative magnitude may appear
     */
    public static Optional<BigInteger> value(String text, boolean isLong, boolean negated) {
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
        var magnitude = new BigInteger(digits, radix);
        int bits = isLong ? 64 : 32;
        var signBit = BigInteger.ONE.shiftLeft(bits - 1);
        if (radix == 10) {
            if (magnitude.compareTo(signBit) < 0 || magnitude.equals(signBit) && negated) return Optional.of(magnitude);
            return Optional.empty();
        }
        if (magnitude.bitLength() > bits) return Optional.empty();
        return Optional.of(magnitude.testBit(bits - 1) ? magnitude.subtract(BigInteger.ONE.shiftLeft(bits)) : magnitude);
    }
}
