// entry: method m
// expect-message: class inheritance is not supported on-chain
import java.math.BigInteger;
class Base { }
class F extends Base { static BigInteger m(BigInteger a) { return a; } }
