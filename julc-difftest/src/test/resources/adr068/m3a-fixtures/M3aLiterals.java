package m3a.fixtures;

import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/**
 * ADR-068 M3a: every literal the slice lowers, each in a method of its own, so I1b compares it by itself. The recorded
 * corpus holds few of these shapes in a program the slice covers.
 */
@SpendingValidator
class M3aLiterals {
    static long zero() { return 0; }
    static long five() { return 5; }
    static long maxInt() { return 2147483647; }
    static long hexMaxInt() { return 0x7FFFFFFF; }
    static long hexMinusOne() { return 0xFFFFFFFF; }
    static long hexMinInt() { return 0x80000000; }
    static long octal() { return 017; }
    static long binary() { return 0b101; }
    static long underscored() { return 1_000; }
    static long longLiteral() { return 5L; }
    static long maxLong() { return 9223372036854775807L; }
    static long hexMinusOneLong() { return 0xFFFFFFFFFFFFFFFFL; }
    static long negative() { return -5; }
    static long spaced() { return - 5; }
    static long commented() { return -/* the sign */5; }
    static long minInt() { return -2147483648; }
    static long minLong() { return -9223372036854775808L; }
    static long escapedSign() { return -7; }
    static long parenthesized() { return ((5)); }
    static long parenthesizedNegative() { return (-5); }
    static boolean yes() { return true; }
    static boolean no() { return false; }
    static String empty() { return ""; }
    static String text() { return "a\tb\"c\\d\101\se"; }
    static String unicode() { return "é😀😀A"; }
    static byte[] noBytes() { return new byte[]{}; }
    static byte[] bytes() { return new byte[]{1, 0x7F, -0x10, - 1, -128, 127, 0, 017, 0b11, -0}; }
    static BigInteger integerZero() { return BigInteger.ZERO; }
    static BigInteger integerOne() { return BigInteger.ONE; }
    static BigInteger integerTwo() { return BigInteger.TWO; }
    static BigInteger integerTen() { return BigInteger.TEN; }
    static PlutusData unit() { return PlutusData.UNIT; }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
