import java.math.BigInteger;

@SpendingValidator
class TestValidator {
    record MyDatum(BigInteger amount) {}

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        MyDatum datum = PlutusData.cast(redeemer, redeemer);
        return datum.amount() == 42;
    }
}
