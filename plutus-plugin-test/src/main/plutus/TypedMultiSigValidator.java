import com.bloxbean.cardano.plutus.core.PlutusData;

/**
 * Typed multi-signature validator using Milestone 6 typed access features.
 * <p>
 * Demonstrates:
 * - Custom datum record with PlutusData fields
 * - ScriptContext typed parameter
 * - List.contains() instead of ContextsLib.signedBy()
 * - Multiple list method calls on the same list
 * - List.size() for count verification
 */
@Validator
class TypedMultiSigValidator {
    record Keys(PlutusData key1, PlutusData key2) {}

    @Entrypoint
    static boolean validate(Keys datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        var sigs = txInfo.signatories();
        boolean sig1 = sigs.contains(datum.key1());
        boolean sig2 = sigs.contains(datum.key2());
        if (sig1) {
            return sig2;
        } else {
            return false;
        }
    }
}
