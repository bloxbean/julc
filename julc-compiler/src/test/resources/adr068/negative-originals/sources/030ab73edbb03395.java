import java.math.BigInteger;
import com.example.lib.Limits;
@SpendingValidator
class UsesLimits {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return Limits.within(Builtins.unIData(redeemer));
    }
}