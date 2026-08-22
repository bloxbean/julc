import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

@SpendingValidator
class VulnerableThresholdGate {
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
        return signers.contains(KEY_A)
                || signers.contains(KEY_B)
                || signers.contains(KEY_C);
    }
}
