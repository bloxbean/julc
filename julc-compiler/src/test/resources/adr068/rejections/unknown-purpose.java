// entry: validator
// expect: JULC0085
// expect-message: expression not allowed as annotation value
import java.math.BigInteger;
import org.julclang.ledger.ScriptContext;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Purpose;
@MultiValidator
class F {
    @Entrypoint(purpose = Purpose.valueOf("SPEND"))
    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
}
