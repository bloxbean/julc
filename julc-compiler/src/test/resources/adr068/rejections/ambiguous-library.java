// entry: method m
// note: Java has no such name without an import, so this stops there. Two imports of a library class of one name are ambiguous-wildcard-import.java.
// expect: JULC0085
// expect-message: (?s)cannot find symbol.*variable Util.*compiler\.err\.cant\.resolve
import java.math.BigInteger;
class F { static BigInteger m(BigInteger a) { return Util.twice(a); } }
//// library
package a;
import java.math.BigInteger;
@OnchainLibrary public class Util { public static BigInteger twice(BigInteger x) { return x.add(x); } }
//// library
package b;
import java.math.BigInteger;
@OnchainLibrary public class Util { public static BigInteger twice(BigInteger x) { return x.add(x); } }
