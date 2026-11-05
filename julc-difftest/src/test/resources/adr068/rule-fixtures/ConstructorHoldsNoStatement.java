package rules;

import java.math.BigInteger;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** The subset's rule: a constructor holds no statement. This one is rejected ({@code JULC0099}). */
@SpendingValidator
class ConstructorHoldsNoStatement {
    record Amount(BigInteger value) {
        Amount {
            value = value.max(BigInteger.ONE);
        }
    }

    @Entrypoint
    static boolean validate(Amount redeemer, ScriptContext ctx) {
        return redeemer.value().compareTo(BigInteger.ONE) < 0;
    }
}
