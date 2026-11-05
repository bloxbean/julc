import java.math.BigInteger;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
class Names {
static BigInteger base() { return BigInteger.TEN; }
static final BigInteger FEE = base();
static BigInteger m(BigInteger a) { return a.add(FEE); } }