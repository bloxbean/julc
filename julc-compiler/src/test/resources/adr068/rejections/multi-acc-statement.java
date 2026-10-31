// entry: method m
// expect-message: Unsupported in multi-acc (loop|break-aware) body:
import java.math.BigInteger;
import org.julclang.core.types.JulcList;
class F {
    static BigInteger m(JulcList<BigInteger> xs) {
        BigInteger s = BigInteger.ZERO;
        BigInteger t = BigInteger.ZERO;
        for (BigInteger x : xs) { s = s.add(x); t = t.add(x); switch (x.intValue()) { case 1 -> s = s.add(x); default -> t = t.add(x); } }
        return s.add(t);
    }
}
