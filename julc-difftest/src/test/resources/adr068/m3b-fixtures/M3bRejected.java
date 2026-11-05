package m3b.fixtures;

import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 M3b: a static initializer that calls a method of its class is JULC0057 on both frontends. */
@SpendingValidator
class M3bRejected {
    static BigInteger helper() { return BigInteger.TWO; }
    static final BigInteger VALUE = helper();

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return VALUE.signum() > 0;
    }
}
