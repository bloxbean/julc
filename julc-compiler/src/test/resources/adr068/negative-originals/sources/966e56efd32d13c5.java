@SpendingValidator
class TestValidator {
    @Param org.julclang.core.PlutusData.BytesData x;

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
