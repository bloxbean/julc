// entry: describe lib.L
// expect-message: Raw or invalid container signature
package lib;
import java.math.BigInteger;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.annotation.OnchainLibrary;
@OnchainLibrary public class L { public static BigInteger f(JulcList xs) { return BigInteger.ONE; } }
