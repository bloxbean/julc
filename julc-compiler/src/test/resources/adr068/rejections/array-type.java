// entry: method m
// expect: JULC0014
// expect-message: arrays are not supported on-chain
import java.math.BigInteger;
class F { static BigInteger m(BigInteger[] xs) { return BigInteger.ONE; } }
