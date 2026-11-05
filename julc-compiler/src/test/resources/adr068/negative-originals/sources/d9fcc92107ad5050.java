import java.math.BigInteger;
@SpendingValidator
class SharedName {
    static boolean validate(BigInteger a) { return a.signum() > 0; }
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return validate(Builtins.unIData(redeemer));
    }
}