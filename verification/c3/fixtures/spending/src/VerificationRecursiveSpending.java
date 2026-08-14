import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

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
