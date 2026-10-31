// entry: method m
// expect-message: Unsupported unary operator: 
import java.math.BigInteger;
class F { static long m(long a) { return ~a; } }
