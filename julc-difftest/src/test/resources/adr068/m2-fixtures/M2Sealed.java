package m2.fixtures;

import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/**
 * ADR-068 M2: implicit permits with a nested record that is not a variant, and a nested user type named like a ledger
 * hash, as a field type.
 */
sealed interface Action {
    record Note(BigInteger v) {}
    record Pay(PubKeyHash to, Note note) implements Action {}
    record Stop() implements Action {}
}

@SpendingValidator
class M2Sealed {
    record PolicyId(byte[] raw) {}
    record Datum(PolicyId policy, Action action) {}

    @Entrypoint
    static boolean validate(Datum datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
