// entry: method m
// expect: JULC0085
// expect-message: (?s)cannot find symbol.*method frobnicate
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { return frobnicate(a); } }
