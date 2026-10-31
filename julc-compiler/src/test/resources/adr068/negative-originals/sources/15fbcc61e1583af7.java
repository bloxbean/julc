@SpendingValidator
class MyValidator {
    record BadRecord(UnknownTypeName field) {}

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
