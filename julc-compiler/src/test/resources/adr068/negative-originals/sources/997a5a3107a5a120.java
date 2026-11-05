@SpendingValidator
@MintingValidator
class DualAnnotation {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
