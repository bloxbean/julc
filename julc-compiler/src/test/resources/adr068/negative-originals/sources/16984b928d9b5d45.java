@SpendingValidator
class MyValidator {
    record BadRecord(float value) {}

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
