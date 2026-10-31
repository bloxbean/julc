import java.math.BigInteger;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Purpose;
@MultiValidator
class Handlers {
    static BigInteger total = BigInteger.ZERO;
    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(PlutusData redeemer, ScriptContext ctx) {
        JulcList<PlutusData> items = Builtins.unListData(redeemer);
        for (var item : items) { total = total.add(Builtins.unIData(item)); }
        return total.compareTo(BigInteger.TEN) > 0;
    }
    @Entrypoint(purpose = Purpose.SPEND)
    static boolean spend(PlutusData redeemer, ScriptContext ctx) {
        return total.compareTo(BigInteger.ZERO) == 0;
    }
}