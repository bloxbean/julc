// entry: method m
// expect-message: Ambiguous type '
import java.math.BigInteger;
class F { static BigInteger m(P p) { return BigInteger.ONE; } }
//// library
package a;
import java.math.BigInteger;
public class A { public record P(BigInteger v) {} public static BigInteger f() { return BigInteger.ONE; } }
//// library
package b;
import java.math.BigInteger;
public class B { public record P(BigInteger v) {} public static BigInteger g() { return BigInteger.ONE; } }
