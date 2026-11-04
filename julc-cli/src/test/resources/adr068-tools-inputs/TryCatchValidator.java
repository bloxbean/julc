package p;

import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

import java.math.BigInteger;

@SpendingValidator
public class TryCatchValidator {
    record Redeemer(BigInteger n) { }

    @Entrypoint
    public static boolean validate(Redeemer r, ScriptContext ctx) {
        try {
            return r.n().signum() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
