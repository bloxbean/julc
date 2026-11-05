// entry: method m
// expect-message: ^JULC0012 Cannot resolve type: \?
import java.math.BigInteger;
import java.util.List;
class F { static BigInteger m(List<?> xs) { return BigInteger.ONE; } }
