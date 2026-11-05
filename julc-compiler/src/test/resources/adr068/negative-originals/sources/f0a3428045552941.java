import org.julclang.stdlib.annotation.MultiValidator;

@MultiValidator
class BadManualDispatch {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
