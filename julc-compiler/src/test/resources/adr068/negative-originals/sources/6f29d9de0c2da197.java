import java.math.BigInteger;
import com.example.lib.Shapes;
@SpendingValidator
class UsesShapes {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return Shapes.side(new Shapes.Box(Builtins.unIData(redeemer))).signum() > 0;
    }
}