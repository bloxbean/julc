@SpendingValidator
class TestValidator {
    @Param PlutusData.BytesData a;
    @Param PlutusData.IntData b;

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
