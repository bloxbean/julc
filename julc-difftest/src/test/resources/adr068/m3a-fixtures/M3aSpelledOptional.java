package m3a.fixtures;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 D7: a type is what it resolves to, so a qualified {@code Optional} is an {@code Optional}. */
@SpendingValidator
class M3aSpelledOptional {
    @Entrypoint
    static boolean validate(java.util.Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
