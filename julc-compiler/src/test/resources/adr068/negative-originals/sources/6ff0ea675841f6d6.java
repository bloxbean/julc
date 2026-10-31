import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcArray;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.lib.ContextsLib;
import java.math.BigInteger;
class MixedArray {
    static PlutusData mixed() {
        var m = JulcArray.of(BigInteger.ONE, new byte[]{1});
        return m.get(0);
    }
}
