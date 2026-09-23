import java.math.BigInteger;

@SpendingValidator
class VestingValidator {
    record VestingDatum(PlutusData beneficiary, PlutusData deadline) {}

    static boolean isPastDeadline(BigInteger currentTime, BigInteger deadline) {
        return currentTime > deadline;
    }

    @Entrypoint
    static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        PlutusData pkh = datum.beneficiary();
        return ContextsLib.signedBy(txInfo, pkh);
    }
}
