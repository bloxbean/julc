package rules;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Purpose;

/** The subset's rule, for a multi-validator: every entry point returns boolean ({@code JULC0094}). */
@MultiValidator
class EveryEntryPointReturnsBoolean {
    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }

    @Entrypoint(purpose = Purpose.WITHDRAW)
    static long withdraw(PlutusData redeemer, ScriptContext ctx) {
        return 1;
    }
}
