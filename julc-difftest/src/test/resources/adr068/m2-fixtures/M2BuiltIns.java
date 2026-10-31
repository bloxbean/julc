package m2.fixtures;

import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.core.types.Tuple2;
import org.julclang.core.types.Tuple3;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/**
 * ADR-068 M2: built-in component types no recorded corpus type holds. {@code AssetEntry} is not here: the reference has
 * no on-chain constructor for it, so a program cannot produce its Data; {@code JavacTypesTest} checks its layout.
 */
record Built(Tuple2<BigInteger, byte[]> pair, Tuple3<BigInteger, byte[], String> triple, Boolean flag) {}

@SpendingValidator
class M2BuiltIns {
    @Entrypoint
    static boolean validate(Built datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
