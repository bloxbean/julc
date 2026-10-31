// entry: method m
// expect-message: synchronized is not supported on-chain
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { synchronized (a) { return a; } } }
