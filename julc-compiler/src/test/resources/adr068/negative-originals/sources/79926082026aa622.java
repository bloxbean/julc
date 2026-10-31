@SpendingValidator
class TestValidator {
    @Param PlutusData.BytesData x;

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
