// entry: validator
// expect-message: Unknown purpose '
import java.math.BigInteger;
@MultiValidator
class F {
    @Entrypoint(purpose = Purpose.valueOf("SPEND"))
    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
}
