import java.math.BigInteger;
import org.julclang.core.types.JulcList;
@SpendingValidator
class ParamUpdate {
    @Param BigInteger limit;
    static boolean within(BigInteger v) { return v.compareTo(limit) <= 0; }
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        JulcList<PlutusData> items = Builtins.unListData(redeemer);
        for (var item : items) { limit = limit.add(Builtins.unIData(item)); }
        return within(BigInteger.valueOf(50));
    }
}