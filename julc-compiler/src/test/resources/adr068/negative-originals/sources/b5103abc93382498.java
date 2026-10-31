@WithdrawValidator
class BadWithdraw {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
