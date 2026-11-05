// entry: method m
// note: Java has no such name without an import, so this stops there. Two imports of a type of one name are ambiguous-wildcard-import.java.
// expect: JULC0085
// expect-message: (?s)cannot find symbol.*class P.*compiler\.err\.cant\.resolve
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
