// entry: method m
// expect: JULC0016
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { throw new IllegalStateException("no"); } }
