import java.math.BigInteger;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.lib.ContextsLib;

public class Probe {
    public static long m(long n) {
        done: {
            Builtins.trace("n");
        }
        return n;
    }
}
