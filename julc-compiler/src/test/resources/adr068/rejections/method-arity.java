// entry: method m
// expect: JULC0085
// expect-message: method contains in interface .*JulcList<T> cannot be applied to given types
import java.math.BigInteger;
import org.julclang.core.types.JulcList;
class F { static boolean m(JulcList<BigInteger> xs) { return xs.contains(); } }
