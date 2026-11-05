// entry: method m
// expect-message: 'this' is not supported on-chain
import java.math.BigInteger;
class F { BigInteger v; BigInteger m(BigInteger a) { return this.v; } }
