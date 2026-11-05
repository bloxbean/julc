package rules;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** The subset's rule: an entry point returns boolean. This one is rejected ({@code JULC0094}). */
@SpendingValidator
class EntryPointReturnsBoolean {
    @Entrypoint
    static long validate(PlutusData redeemer, ScriptContext ctx) {
        return 1;
    }
}
