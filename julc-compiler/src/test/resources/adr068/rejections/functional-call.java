// entry: method m
// expect-message: Functional interface \.apply\(\) is not supported
import java.math.BigInteger;
import java.util.function.Function;
class F { static BigInteger m(BigInteger a) { Function<BigInteger, BigInteger> f = x -> x; return f.apply(a); } }
