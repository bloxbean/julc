// entry: method m
// expect-message: Unknown method: 
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { return frobnicate(a); } }
