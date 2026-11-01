package m3a.fixtures;

import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 M3a: names, static constants, {@code @Param} fields and shadowing, with an entry point that reads them. */
@SpendingValidator
class M3aNames {
    @Param static BigInteger first;
    @Param static boolean second;
    @Param static byte[] third, fourth;

    static final long FIVE = 5;
    static long seven = 7;
    static final String WORD = "word";
    static final byte[] BYTES = new byte[]{1, 2};
    static final BigInteger COPY_OF_FIRST = first;
    static final long ALIAS = FIVE, ALIAS_OF_ALIAS = ALIAS;
    static final boolean OK = second;
    static final PlutusData NOTHING = PlutusData.UNIT;
    static final long NEGATIVE = -5;
    static final BigInteger TEN = BigInteger.TEN;

    static BigInteger readsAParam() { return first; }
    static boolean readsAParamCopy() { return OK; }
    static byte[] readsTheLastParam() { return fourth; }
    static long readsAStatic() { return ALIAS_OF_ALIAS; }
    static long readsANegative() { return NEGATIVE; }
    static BigInteger readsAnApiConstant() { return TEN; }
    static long secondOfTwo(long a, long b) { return b; }
    static long shadowsAStatic(long FIVE) { return FIVE; }
    static BigInteger shadowsAParam(BigInteger first) { return first; }
    static long oneShadowedOneNot(long FIVE, long a) { return seven; }
    static void nothing() { }
    static void returnsNothing() { return; }
    long instanceMethodIsNotLowered() { return 1; }

    @Entrypoint
    static boolean validate(boolean redeemer, ScriptContext ctx) {
        return OK;
    }
}
