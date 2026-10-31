package m3b.fixtures;

import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 M3b: every operator, D6's binding of {@code >} and {@code >=}, and short-circuiting. */
@SpendingValidator
class M3bOperators {
    static final long K = 3 + 4 * 5;
    static final String GREETING = "hello, world";
    static final boolean BOTH = K > 1 && K < 100;

    static long said(String message, long value) { return Builtins.unIData(Builtins.trace(message, Builtins.iData(value))).longValue(); }

    static long add(long a, long b) { return a + b; }
    static long sub(long a, long b) { return a - b; }
    static long mul(long a, long b) { return a * b; }
    static long div(long a, long b) { return a / b; }
    static long rem(long a, long b) { return a % b; }
    static boolean mixedWidths(int a, long b) { return a + 1 < b * 2; }
    static long neg(long a) { return -a; }
    static long negParen(long a) { return -(a + 1); }
    static long negNeg(long a) { return - -a; }
    static boolean not(boolean a) { return !a; }
    static boolean notNot(boolean a) { return !!a; }
    static String concat(String a, String b) { return a + b; }

    static boolean lt(long a, long b) { return a < b; }
    static boolean le(long a, long b) { return a <= b; }
    static boolean gt(long a, long b) { return a > b; }
    static boolean ge(long a, long b) { return a >= b; }
    static boolean eq(long a, long b) { return a == b; }
    static boolean ne(long a, long b) { return a != b; }
    static boolean boolEq(boolean a, boolean b) { return a == b; }
    static boolean boolNe(boolean a, boolean b) { return a != b; }
    static boolean constantStrings() { return "a" == "a"; }
    static boolean constantStringAlias() { return GREETING != "hello, world"; }

    static boolean gtBothNonTrivial(long a, long b) { return said("L", a) > said("R", b); }
    static boolean geBothNonTrivial(long a, long b) { return said("L", a) >= said("R", b); }
    static boolean gtLeftTrivial(long a, long b) { return a > said("R", b); }
    static boolean gtRightTrivial(long a, long b) { return said("L", a) > b; }
    static boolean gtConstantRight(long a) { return said("L", a) > 5; }
    static boolean gtNested(long a, long b, long c) { return (a + b) > (b + c) == (a * c) >= (c - a); }

    static boolean and(boolean a, boolean b) { return a && b; }
    static boolean or(boolean a, boolean b) { return a || b; }
    static boolean andTraced(long a) { return said("L", a) > 0 && said("R", a) > 1; }
    static boolean orTraced(long a) { return said("L", a) > 0 || said("R", a) > 1; }
    static boolean mixed(boolean a, boolean b, boolean c) { return a && b || !c; }

    static long ternary(boolean c, long a, long b) { return c ? a : b; }
    static long ternaryTraced(boolean c, long a) { return c ? said("T", a) : said("F", a); }
    static String ternaryStrings(boolean c) { return c ? "yes" : "no"; }
    static boolean ternaryBooleans(boolean c, boolean a) { return c ? a : !a; }
    static long nestedTernary(long a) { return a > 0 ? a > 10 ? 2 : 1 : 0; }
    static PlutusData ternaryData(boolean c, PlutusData a) { return c ? a : PlutusData.UNIT; }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return BOTH;
    }
}
