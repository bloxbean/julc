import org.julclang.stdlib.annotation.NewType;

@SpendingValidator
class TestValidator {
    @NewType
    record Bad(byte[] a, byte[] b) {}

    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        return true;
    }
}
