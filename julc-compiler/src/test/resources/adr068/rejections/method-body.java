// entry: method m
// expect-message: Method must have a body:
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { return helper(a); } static native BigInteger helper(BigInteger a); }
