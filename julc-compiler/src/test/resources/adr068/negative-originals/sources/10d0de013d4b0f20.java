import java.util.List;
import org.julclang.stdlib.annotation.NewType;

@SpendingValidator
class TestValidator {
    @NewType
    record Bad(List<byte[]> items) {}

    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        return true;
    }
}
