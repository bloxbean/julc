import java.math.BigInteger;

@SpendingValidator
class SimpleSpending {
    @Param static byte[] authorizedSigner;

    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        return ContextsLib.signedBy(txInfo, authorizedSigner);
    }
}
