// entry: method m
// expect: JULC0085
// expect-message: reference to Q is ambiguous
import java.math.BigInteger;
import a.*;
import b.*;
class F { static BigInteger m(Q q) { return BigInteger.ONE; } }
//// library
package a;
import java.math.BigInteger;
public record Q(BigInteger v) {}
//// library
package b;
import java.math.BigInteger;
public record Q(BigInteger w) {}
