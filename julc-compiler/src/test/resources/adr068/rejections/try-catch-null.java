// entry: method m
// expect: JULC0015
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { try { return a; } catch (RuntimeException e) { return null; } } }
