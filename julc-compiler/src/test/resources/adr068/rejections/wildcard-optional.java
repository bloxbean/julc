// entry: method m
// expect-message: ^JULC0012 Cannot resolve type: \?
import java.math.BigInteger;
import java.util.Optional;
class F { static BigInteger m(Optional<?> o) { return BigInteger.ONE; } }
