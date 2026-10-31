// entry: method m
// expect-message: ^null Cannot resolve type: \?
import java.math.BigInteger;
import java.util.Optional;
class F { static BigInteger m(Optional<?> o) { return BigInteger.ONE; } }
