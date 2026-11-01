package m3a.fixtures;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 D7: a type is what it resolves to, and a user record named {@code OptionalThing} is a record. */
@SpendingValidator
class M3aUserOptionalThing {
    record OptionalThing(long value) { }

    @Entrypoint
    static boolean validate(OptionalThing datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
