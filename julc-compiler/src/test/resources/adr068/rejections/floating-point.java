// entry: method m
// expect-message: floating point types \(float/double\) are not supported
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { double d = 1.5; return a; } }
