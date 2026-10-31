// entry: method m
// expect-message: arrays are not supported on-chain
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { BigInteger[] xs = new BigInteger[2]; return a; } }
