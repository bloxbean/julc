@SpendingValidator
class TestValidator {
    @Param PlutusData.MapData x;

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
