import java.math.BigInteger;
import org.julclang.core.types.JulcList;
@OnchainLibrary
class Counter {
    static BigInteger seen = BigInteger.ZERO;
    static BigInteger count(JulcList<PlutusData> items) {
        for (var item : items) { seen = seen.add(BigInteger.ONE); }
        return seen;
    }
}