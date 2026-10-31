import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Purpose;

@MultiValidator
class MixedPurposes {
    @Entrypoint
    static boolean handleDefault(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }

    @Entrypoint(purpose = Purpose.MINT)
    static boolean handleMint(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
