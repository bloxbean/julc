import java.math.BigInteger;
import org.julclang.core.types.JulcList;

@SpendingValidator
class Probe {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        JulcList<BigInteger> xs = JulcList.of(BigInteger.ONE);
        long c = 0;
        for (var x : xs) {
            if (x.signum() == 0) { continue; }
            c = c + 1;
        }
        return c > 0;
    }
}
