import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

import java.math.BigInteger;

@SpendingValidator
class VerificationRecursiveSpending {
    sealed interface Chain permits End, Cons {}
    record End() implements Chain {}
    record Cons(BigInteger value, Chain next) implements Chain {}
    record Redeemer(BigInteger expectedHead) {}

    @Entrypoint
    static boolean validate(Chain datum, Redeemer redeemer, ScriptContext ctx) {
        return true;
    }
}
