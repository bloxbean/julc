import java.math.BigInteger;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
class Names {
static BigInteger amount(PlutusData d) { return Builtins.unIData(d); }
static BigInteger m(PlutusData d) { return d.amount(); } }