// entry: method m
// expect-message: Unsupported primitive type:
import java.math.BigInteger;
class F { static char m(BigInteger a) { char c = 'x'; return c; } }
