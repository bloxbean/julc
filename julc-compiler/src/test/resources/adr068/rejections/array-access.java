// entry: method m
// expect-message: array access is not supported on-chain
import java.math.BigInteger;
class F { static BigInteger m(byte[] b) { return BigInteger.valueOf(b[0]); } }
