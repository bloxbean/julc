// entry: method m
// expect: JULC0085
// expect-message: non-static variable super cannot be referenced
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { Object o = super.toString(); return a; } }
