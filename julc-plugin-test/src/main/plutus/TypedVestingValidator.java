import com.bloxbean.cardano.julc.core.PlutusData;
import java.math.BigInteger;

/**
 * Typed vesting validator using Milestone 6 typed access features.
 * <p>
 * Demonstrates:
 * - Custom datum record with typed fields (byte[], BigInteger)
 * - ScriptContext as typed parameter
 * - Typed TxInfo field access: ctx.txInfo()
 * - List instance method: txInfo.signatories().contains(datum.beneficiary())
 * - Custom record field access: datum.deadline()
 */
@Validator
class TypedVestingValidator {
    record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

    @Entrypoint
    static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        var sigs = txInfo.signatories();
        boolean hasSigner = sigs.contains(datum.beneficiary());
        BigInteger deadline = datum.deadline();
        return hasSigner && deadline > 0;
    }
}
