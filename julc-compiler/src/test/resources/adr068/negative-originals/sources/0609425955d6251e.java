@SpendingValidator
class MyValidator {
    record MyDatum(Strng name) {}

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
