package rules;

import java.math.BigInteger;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** The subset's rule: a cast to boolean takes a boolean or Data. This one is rejected ({@code JULC0098}). */
@SpendingValidator
class BooleanCastTakesABoolean {
    @Entrypoint
    static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        return (boolean) (Object) redeemer;
    }
}
