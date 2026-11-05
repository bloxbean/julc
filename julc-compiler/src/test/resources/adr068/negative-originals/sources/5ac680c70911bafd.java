import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Purpose;

@MultiValidator
class DupPurpose {
    @Entrypoint(purpose = Purpose.MINT)
    static boolean handleMint1(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }

    @Entrypoint(purpose = Purpose.MINT)
    static boolean handleMint2(PlutusData redeemer, ScriptContext ctx) {
        return false;
    }
}
