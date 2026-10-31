import java.math.BigInteger;
import org.julclang.core.types.JulcList;
import demo.Seq;
@MintingValidator
class UsesTemplate {
    @Entrypoint
    static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        JulcList<BigInteger> items = JulcList.of(redeemer);
        return Seq.firstOr(items, BigInteger.ZERO).equals(BigInteger.ONE);
    }
}
