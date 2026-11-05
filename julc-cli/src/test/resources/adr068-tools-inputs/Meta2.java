package p;
import org.julclang.stdlib.annotation.*;
import java.math.BigInteger;
@SpendingValidator
class Meta2 {
    record Datum(BigInteger n) { }
    @Entrypoint
    static boolean validate(Datum d, BigInteger r, PlutusData ctx) {
        try { return d.n().signum() > 0; } catch (Exception e) { return false; }
    }
}
