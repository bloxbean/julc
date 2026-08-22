import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

@SpendingValidator
class AuthorizedThresholdGate {
    static final byte[] KEY_A = new byte[] {
        65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65,65};
    static final byte[] KEY_B = new byte[] {
        66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66};
    static final byte[] KEY_C = new byte[] {
        67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67,67};

    record Datum() {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        var signers = ctx.txInfo().signatories();
        if (signers.isEmpty() || signers.tail().isEmpty()
                || !signers.tail().tail().isEmpty()) {
            return false;
        }

        byte[] first = signers.head().hash();
        byte[] second = signers.tail().head().hash();
        boolean firstApproved = Builtins.equalsByteString(first, KEY_A)
                || Builtins.equalsByteString(first, KEY_B)
                || Builtins.equalsByteString(first, KEY_C);
        boolean secondApproved = Builtins.equalsByteString(second, KEY_A)
                || Builtins.equalsByteString(second, KEY_B)
                || Builtins.equalsByteString(second, KEY_C);
        return firstApproved && secondApproved
                && !Builtins.equalsByteString(first, second);
    }
}
