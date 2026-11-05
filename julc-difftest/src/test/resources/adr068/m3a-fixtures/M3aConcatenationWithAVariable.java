package m3a.fixtures;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 M3a: a {@code +} of a variable and two literals: nothing joins the literals. */
@SpendingValidator
class M3aConcatenationWithAVariable {
    static String joined(String x) { return x + "c" + "d"; }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
