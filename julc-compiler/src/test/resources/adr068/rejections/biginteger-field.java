// entry: method m
// expect-message: Unsupported BigInteger field:
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { return a.add(BigInteger.MINUS_ONE); } }
