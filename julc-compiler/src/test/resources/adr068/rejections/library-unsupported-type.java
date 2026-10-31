// entry: describe lib.L
// expect-message: Unsupported on-chain source type
package lib;
import java.math.BigInteger;
import org.julclang.core.types.Tuple2;
@OnchainLibrary public class L { public static BigInteger f(Tuple2<BigInteger, BigInteger> x) { return BigInteger.ONE; } }
