// entry: method m
// expect-message: ^null Cannot resolve type: \?
import java.math.BigInteger;
import java.util.List;
class F { static BigInteger m(List<?> xs) { return BigInteger.ONE; } }
