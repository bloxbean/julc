import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.Builtins;

public class Probe {
    sealed interface T permits C, D {}
    record C(BigInteger w) implements T {}
    record D(BigInteger w) implements T {}
    sealed interface U permits P, Q {}
    record P(T t) implements U {}
    record Q(BigInteger z) implements U {}
    static BigInteger m(U u) {
        return switch (u) {
            case P(C(var w)) -> w;
            case P p -> BigInteger.TWO;
            case Q q -> q.z();
        };
    }

}
