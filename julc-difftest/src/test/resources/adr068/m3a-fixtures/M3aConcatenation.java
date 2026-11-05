package m3a.fixtures;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 M3a: a {@code +} of string literals is an operator, and javac's tree keeps it. */
@SpendingValidator
class M3aConcatenation {
    static String joined() { return "a" + "b"; }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
