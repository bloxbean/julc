package m3a.fixtures;

import java.math.BigInteger;
import java.util.Optional;
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.Purpose;

/** ADR-068 M3a: auto-dispatch, each handler with the statics and helpers around it, and a parameter shadowing a static. */
@MultiValidator
class M3aMulti {
    @Param static BigInteger limit;

    static final boolean mintAllowed = true;
    static final BigInteger COPY = limit;

    static BigInteger helper(BigInteger x) { return COPY; }

    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(PlutusData redeemer, ScriptContext ctx) {
        return mintAllowed;
    }

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean spend(Optional<PlutusData> datum, boolean mintAllowed, ScriptContext ctx) {
        return mintAllowed;
    }

    @Entrypoint(purpose = Purpose.WITHDRAW)
    static boolean withdraw(PlutusData redeemer, ScriptContext ctx) {
        return false;
    }
}
