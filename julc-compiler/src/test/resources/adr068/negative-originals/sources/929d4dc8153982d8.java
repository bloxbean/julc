import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.Builtins;

public class Probe {
    sealed interface S permits A, B {}
    record A(BigInteger v) implements S {}
    record B(BigInteger v) implements S {}
    record W(B b) {}
    sealed interface V permits X, Y {}
    record X(W w) implements V {}
    record Y(BigInteger z) implements V {}
    static BigInteger m(V val) {
        return switch (val) {
            case X(W(B(var v))) -> v;
            default -> BigInteger.ZERO;
        };
    }

}
