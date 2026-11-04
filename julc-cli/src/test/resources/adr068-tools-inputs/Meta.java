package p;
import org.julclang.stdlib.annotation.*;
import java.math.BigInteger;
import java.util.*;
@SpendingValidator
class Meta {
    @Param static byte[] owner;
    @Param static Map<byte[], List<BigInteger>> table;
    @Param static java.math.BigInteger qualified;
    @Param static @Deprecated BigInteger annotated;
    record Datum(byte[] owner, Map<String, ? extends Number> m, int... rest) { }
    sealed interface Act permits Act.Go, Act.Stop {
        record Go(BigInteger amount) implements Act { }
        record Stop() implements Act { }
    }
    @Entrypoint
    static boolean validate(Datum d, Act r, PlutusData ctx) { return true; }
}
