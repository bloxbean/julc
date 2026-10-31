// entry: method m
// expect-message: requires (an index|one|a count|a function|a predicate|a key|key and value|a policyId|policyId and tokenName) argument
import java.math.BigInteger;
import org.julclang.core.types.JulcList;
class F { static boolean m(JulcList<BigInteger> xs) { return xs.contains(); } }
