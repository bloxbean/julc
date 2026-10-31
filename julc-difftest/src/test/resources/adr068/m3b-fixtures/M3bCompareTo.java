package m3b.fixtures;

import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 D6: the twelve {@code compareTo} forms, the forms outside the rule, and traced operands. */
@SpendingValidator
class M3bCompareTo {
    static BigInteger said(String message, BigInteger value) { return Builtins.unIData(Builtins.trace(message, Builtins.iData(value))); }

    static boolean lt(BigInteger a, BigInteger b) { return a.compareTo(b) < 0; }
    static boolean le(BigInteger a, BigInteger b) { return a.compareTo(b) <= 0; }
    static boolean gt(BigInteger a, BigInteger b) { return a.compareTo(b) > 0; }
    static boolean ge(BigInteger a, BigInteger b) { return a.compareTo(b) >= 0; }
    static boolean eq(BigInteger a, BigInteger b) { return a.compareTo(b) == 0; }
    static boolean ne(BigInteger a, BigInteger b) { return a.compareTo(b) != 0; }
    static boolean zeroGt(BigInteger a, BigInteger b) { return 0 > a.compareTo(b); }
    static boolean zeroGe(BigInteger a, BigInteger b) { return 0 >= a.compareTo(b); }
    static boolean zeroLt(BigInteger a, BigInteger b) { return 0 < a.compareTo(b); }
    static boolean zeroLe(BigInteger a, BigInteger b) { return 0 <= a.compareTo(b); }
    static boolean zeroEq(BigInteger a, BigInteger b) { return 0 == a.compareTo(b); }
    static boolean zeroNe(BigInteger a, BigInteger b) { return 0 != a.compareTo(b); }

    static boolean tracedGt(BigInteger a, BigInteger b) { return said("L", a).compareTo(said("R", b)) > 0; }
    static boolean tracedGe(BigInteger a, BigInteger b) { return said("L", a).compareTo(said("R", b)) >= 0; }
    static boolean tracedLt(BigInteger a, BigInteger b) { return said("L", a).compareTo(said("R", b)) < 0; }
    static boolean tracedZeroLt(BigInteger a, BigInteger b) { return 0 < said("L", a).compareTo(said("R", b)); }
    static boolean tracedLeftOnly(BigInteger a, BigInteger b) { return said("L", a).compareTo(b) > 0; }
    static boolean constantArgument(BigInteger a) { return a.compareTo(BigInteger.TEN) >= 0; }

    static boolean parenthesizedCall(BigInteger a, BigInteger b) { return (a.compareTo(b)) > 0; }
    static boolean parenthesizedZero(BigInteger a, BigInteger b) { return a.compareTo(b) > (0); }
    static boolean longZero(BigInteger a, BigInteger b) { return a.compareTo(b) > 0L; }
    static boolean negatedZero(BigInteger a, BigInteger b) { return a.compareTo(b) > -0; }
    static boolean hexZero(BigInteger a, BigInteger b) { return a.compareTo(b) > 0x0; }
    static boolean againstOne(BigInteger a, BigInteger b) { return a.compareTo(b) == 1; }
    static long asAValue(BigInteger a, BigInteger b) { return a.compareTo(b); }
    static long rawLong(long a, long b) { return BigInteger.valueOf(a).compareTo(BigInteger.valueOf(b)); }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return ctx.txInfo().fee().compareTo(BigInteger.TWO) > 0;
    }
}
