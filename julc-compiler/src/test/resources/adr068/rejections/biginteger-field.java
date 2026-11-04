// entry: method m
// expect: JULC0085
// expect-message: (?s)cannot find symbol.*variable MINUS_ONE
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { return a.add(BigInteger.MINUS_ONE); } }
