// entry: method m
// expect-message: 'super' is not supported on-chain
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { Object o = super.toString(); return a; } }
