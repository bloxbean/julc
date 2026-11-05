// entry: method m
// expect-message: ^JULC0012 Cannot resolve type: \?
import java.math.BigInteger;
import java.util.Map;
class F { static BigInteger m(Map<String, ?> m) { return BigInteger.ONE; } }
