// entry: method m
// expect-message: break is only supported inside for-each or while loops
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { switch (a.intValue()) { case 1: break; default: break; } return a; } }
