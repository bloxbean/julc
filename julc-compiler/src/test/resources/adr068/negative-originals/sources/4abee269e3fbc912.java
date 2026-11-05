import java.math.BigInteger;
@SpendingValidator
class OverloadedHelpers {
    static boolean ok(BigInteger a) { return a.signum() > 0; }
    static boolean ok(long a) { return a > 100; }
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return ok(Builtins.unIData(redeemer));
    }
}