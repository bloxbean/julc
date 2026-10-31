import java.math.BigInteger;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
class Names {
static BigInteger m(PlutusData d, BigInteger scale) { BigInteger times = scale; return d.times(scale); } }