// entry: method m
// expect-message: new BigInteger\(\) requires a string literal argument
import java.math.BigInteger;
class F { static BigInteger m(String s) { return new BigInteger(s); } }
