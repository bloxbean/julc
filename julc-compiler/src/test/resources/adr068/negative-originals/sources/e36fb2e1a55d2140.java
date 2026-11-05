@SpendingValidator
class MyValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        try {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
