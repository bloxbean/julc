// entry: method m
// expect-message: Ambiguous library class '
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { return Util.twice(a); } }
//// library
package a;
import java.math.BigInteger;
@OnchainLibrary public class Util { public static BigInteger twice(BigInteger x) { return x.add(x); } }
//// library
package b;
import java.math.BigInteger;
@OnchainLibrary public class Util { public static BigInteger twice(BigInteger x) { return x.add(x); } }
