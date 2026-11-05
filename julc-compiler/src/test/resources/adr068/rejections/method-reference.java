// entry: method m
// expect-message: Method references \(::\) are not supported
import java.math.BigInteger;
import java.util.function.Function;
class F { static BigInteger m(BigInteger a) { Function<BigInteger, BigInteger> f = BigInteger::negate; return a; } }
