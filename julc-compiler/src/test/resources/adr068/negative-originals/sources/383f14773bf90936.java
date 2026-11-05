import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcArray;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.lib.ContextsLib;
import java.math.BigInteger;
class MixedNestedArray {
    static PlutusData mixedNested() {
        var m = JulcArray.of(JulcList.of(BigInteger.ONE), JulcList.of(new byte[]{1}));
        return m.get(0).get(0);
    }
}
