// entry: method m
// expect-message: Cannot construct non-record type:
import java.math.BigInteger;
class F { static class Box { } static BigInteger m(BigInteger a) { Box b = new Box(); return a; } }
