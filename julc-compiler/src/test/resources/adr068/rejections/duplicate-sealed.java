// entry: method m
// expect: JULC0085
// expect-message: duplicate class: a\.H
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { return a; } }
//// library
package a;
import java.math.BigInteger;
public class H { public sealed interface S permits A {} public record A(BigInteger x) implements S {} public static BigInteger f() { return BigInteger.ONE; } }
//// library
package a;
import java.math.BigInteger;
public class H { public sealed interface S permits B {} public record B(BigInteger y) implements S {} public static BigInteger g() { return BigInteger.ONE; } }
