// entry: method m
// expect-message: ^null Cannot resolve type: \? extends String
import java.math.BigInteger;
import java.util.List;
class F { static BigInteger m(List<? extends String> xs) { return BigInteger.ONE; } }
