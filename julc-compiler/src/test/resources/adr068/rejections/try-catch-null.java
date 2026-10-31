// entry: method m
// expect: JULC0015
// expect: JULC0017
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { try { return a; } catch (RuntimeException e) { return null; } } }
