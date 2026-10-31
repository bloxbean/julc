import java.math.BigInteger;

sealed interface Shape permits Circle, Rect, Triangle {}
record Circle(long radius) implements Shape {}
record Rect(long w, long h) implements Shape {}
record Triangle(long a, long b, long c) implements Shape {}

@SpendingValidator
class TestValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        Shape s = (Shape) redeemer;
        return switch (s) {
            case Circle c -> c.radius() > 0;
        };
    }
}
