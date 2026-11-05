import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcValue;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.lib.NativeValueLib;
import java.math.BigInteger;
import java.util.Optional;
@SpendingValidator
class NativeDatumValidator {
    @Entrypoint
    static boolean validate(
            JulcValue datum,
            PlutusData redeemer,
            ScriptContext context) {
        return true;
    }
}
