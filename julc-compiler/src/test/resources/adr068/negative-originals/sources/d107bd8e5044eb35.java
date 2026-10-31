import java.math.BigInteger;
import org.julclang.stdlib.annotation.Param;

class Probe {
    sealed interface S permits A, B {}
    record A(BigInteger v) implements S {}
    record B(BigInteger v) implements S {}
    @Param static final boolean ENABLED = true;
    static BigInteger g(BigInteger a) {
        S s = new A(a);
        return switch (s) { case A x when ENABLED -> BigInteger.ONE; case B y -> BigInteger.ZERO; };
    }
}
