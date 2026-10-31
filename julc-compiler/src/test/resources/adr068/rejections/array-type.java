// entry: method m
// expect-message: Arrays are not supported except byte\[\]
import java.math.BigInteger;
class F { static BigInteger m(BigInteger[] xs) { return BigInteger.ONE; } }
