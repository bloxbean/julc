@SpendingValidator
class TestValidator {
    @Param PlutusData.ListData x;

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
