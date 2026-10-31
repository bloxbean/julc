// entry: method m
// expect-message: Ambiguous type '
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
