@SpendingValidator
class TestValidator {
    @Param BytesData x;

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
