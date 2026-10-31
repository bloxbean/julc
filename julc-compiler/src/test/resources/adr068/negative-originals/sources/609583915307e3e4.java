import java.math.BigInteger;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
class Loops {
    sealed interface Action permits Only {}
    record Only() implements Action {}
    sealed interface PatternAction permits WithValue {}
    record WithValue(BigInteger value) implements PatternAction {}
static BigInteger m(JulcList<BigInteger> xs) { Action action = new Only();
BigInteger acc = BigInteger.ZERO;
for (var x : xs) {
    BigInteger step = switch (action) { case Only o -> {
        BigInteger local = BigInteger.ZERO;
        while (true) {
            BigInteger bodyLocal = BigInteger.ZERO;
            if (true) { bodyLocal = BigInteger.ONE; }
            local = local.add(bodyLocal);
        }
        yield local;
    } };
    acc = acc.add(step);
}
return acc;
 } }