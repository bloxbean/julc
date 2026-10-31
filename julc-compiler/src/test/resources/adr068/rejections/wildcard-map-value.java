// entry: method m
// expect-message: ^null Cannot resolve type: \?
import java.math.BigInteger;
import java.util.Map;
class F { static BigInteger m(Map<String, ?> m) { return BigInteger.ONE; } }
