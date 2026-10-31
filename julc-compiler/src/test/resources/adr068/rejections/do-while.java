// entry: method m
// expect: JULC0019
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { BigInteger s = a; do { s = s.add(a); } while (s.signum() < 0); return s; } }
