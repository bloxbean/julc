import java.math.BigInteger;
@SpendingValidator
class UsesCounter {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return Counter.count(Builtins.unListData(redeemer)).compareTo(BigInteger.ONE) > 0;
    }
}