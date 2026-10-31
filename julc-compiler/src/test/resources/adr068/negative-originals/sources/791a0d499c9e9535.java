import java.math.BigInteger;
@SpendingValidator
class Guarded {
    sealed interface Action permits Pay, Stop {}
    record Pay(BigInteger amount) implements Action {}
    record Stop() implements Action {}
    @Param
    static BigInteger limit;
    @Entrypoint
    static boolean validate(Action redeemer, ScriptContext ctx) {
        if (redeemer instanceof Pay limit && limit.amount().signum() > 0) {
            return true;
        }
        return false;
    }
}