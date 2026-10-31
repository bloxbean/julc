import java.math.BigInteger;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
class Loops {
    sealed interface Action permits Only {}
    record Only() implements Action {}
    sealed interface PatternAction permits WithValue {}
    record WithValue(BigInteger value) implements PatternAction {}
static BigInteger m(JulcList<BigInteger> xs, Action action) { return switch (action) { case Only o -> { if (action instanceof Only p) {} for (var y : xs) { p = new Only(); } yield BigInteger.ZERO; } }; } }