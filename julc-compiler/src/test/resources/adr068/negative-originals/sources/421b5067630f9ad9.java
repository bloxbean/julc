import java.math.BigInteger;
import org.julclang.core.types.JulcList;
@SpendingValidator
class Budget {
    @Param BigInteger limit;
    static BigInteger spend(JulcList<PlutusData> items) {
        for (var item : items) { limit = limit.subtract(Builtins.unIData(item)); }
        return limit;
    }
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return spend(Builtins.unListData(redeemer)).compareTo(BigInteger.ZERO) >= 0;
    }
}