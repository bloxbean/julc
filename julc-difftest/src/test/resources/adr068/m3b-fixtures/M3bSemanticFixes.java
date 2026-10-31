package m3b.fixtures;

import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/**
 * ADR-068 M3b: where javac's types make a program right that the reference gets wrong, so the bytes differ on purpose
 * (D13, semantic fix). Adr068CallsHarnessTest runs each on the JVM.
 */
@SpendingValidator
class M3bSemanticFixes {
    /** #297: the target of a list of lists is compared as Data. */
    static boolean nestedContains(JulcList<JulcList<BigInteger>> l, JulcList<BigInteger> x) { return l.contains(x); }
    static boolean stringsContain(JulcList<String> l, String x) { return l.contains(x); }
    /** A list literal's elements are typed by javac, so its reads decode. */
    static BigInteger literalHead() { return JulcList.of(BigInteger.ONE, BigInteger.TWO).head(); }
    static boolean literalContains() { return JulcList.of(BigInteger.ONE, BigInteger.TWO).contains(BigInteger.TWO); }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
