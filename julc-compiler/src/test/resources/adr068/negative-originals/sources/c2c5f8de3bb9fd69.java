import java.math.BigInteger;
import java.util.List;

@SpendingValidator
class TestValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        List<PlutusData> items = (List<PlutusData>) redeemer;
        boolean found = false;
        for (var item : items) {
            if (item == redeemer) {
                return true;
            }
            found = true;
        }
        return found;
    }
}
