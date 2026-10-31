@SpendingValidator
class TestValidator {
    @Param PlutusData.IntData x;

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
