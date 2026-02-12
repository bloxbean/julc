package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multi-file compilation support.
 * <p>
 * Covers: library method calls, type sharing across files, error handling,
 * backward compatibility, and end-to-end evaluation.
 */
class MultiFileCompilerTest {

    private final JulcCompiler compiler = new JulcCompiler();
    private final JulcVm vm = JulcVm.create();

    private Program compile(String validatorSource, String... librarySources) {
        var result = compiler.compile(validatorSource, List.of(librarySources));
        assertFalse(result.hasErrors(), "Compilation should not have errors: " + result.diagnostics());
        return result.program();
    }

    private EvalResult evaluate(Program program, PlutusData... args) {
        return vm.evaluateWithArgs(program, List.of(args));
    }

    private PlutusData mockCtx(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),
                redeemer,
                PlutusData.integer(0));
    }

    private void assertSuccess(EvalResult result) {
        assertTrue(result.isSuccess(), "Expected success but got: " + result);
    }

    private void assertFailure(EvalResult result) {
        assertFalse(result.isSuccess(), "Expected failure but got: " + result);
    }

    // --- Basic library method calls ---

    @Nested
    class BasicLibraryCalls {

        static final String MATH_LIB = """
                import java.math.BigInteger;

                class MathUtils {
                    static BigInteger max(BigInteger a, BigInteger b) {
                        if (a > b) {
                            return a;
                        } else {
                            return b;
                        }
                    }

                    static BigInteger min(BigInteger a, BigInteger b) {
                        if (a < b) {
                            return a;
                        } else {
                            return b;
                        }
                    }
                }
                """;

        static final String VALIDATOR_USING_LIB = """
                import java.math.BigInteger;

                @Validator
                class AuctionValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger bid = 100;
                        BigInteger minBid = 50;
                        return MathUtils.max(bid, minBid) == 100;
                    }
                }
                """;

        @Test
        void compilesValidatorWithLibrary() {
            var program = compile(VALIDATOR_USING_LIB, MATH_LIB);
            assertNotNull(program);
        }

        @Test
        void evaluatesValidatorWithLibraryMethodCall() {
            var program = compile(VALIDATOR_USING_LIB, MATH_LIB);
            var ctx = mockCtx(PlutusData.integer(0));
            assertSuccess(evaluate(program, ctx));
        }

        @Test
        void evaluatesWithMultipleLibraryMethods() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class MultiMethodValidator {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            BigInteger maxVal = MathUtils.max(10, 20);
                            BigInteger minVal = MathUtils.min(10, 20);
                            return maxVal == 20 && minVal == 10;
                        }
                    }
                    """;
            var program = compile(source, MATH_LIB);
            var ctx = mockCtx(PlutusData.integer(0));
            assertSuccess(evaluate(program, ctx));
        }
    }

    // --- Multiple library files ---

    @Nested
    class MultipleLibraries {

        static final String LIB_A = """
                import java.math.BigInteger;

                class LibA {
                    static BigInteger double_(BigInteger x) {
                        return x + x;
                    }
                }
                """;

        static final String LIB_B = """
                import java.math.BigInteger;

                class LibB {
                    static boolean isPositive(BigInteger x) {
                        return x > 0;
                    }
                }
                """;

        static final String VALIDATOR_USING_BOTH = """
                import java.math.BigInteger;

                @Validator
                class MultiLibValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger doubled = LibA.double_(21);
                        return LibB.isPositive(doubled) && doubled == 42;
                    }
                }
                """;

        @Test
        void compilesWithMultipleLibraries() {
            var program = compile(VALIDATOR_USING_BOTH, LIB_A, LIB_B);
            assertNotNull(program);
        }

        @Test
        void evaluatesWithMultipleLibraries() {
            var program = compile(VALIDATOR_USING_BOTH, LIB_A, LIB_B);
            var ctx = mockCtx(PlutusData.integer(0));
            assertSuccess(evaluate(program, ctx));
        }
    }

    // --- Type sharing across files ---

    @Nested
    class TypeSharing {

        static final String TYPES_LIB = """
                import java.math.BigInteger;

                record Point(BigInteger x, BigInteger y) {}

                class PointUtils {
                    static BigInteger distance(Point p) {
                        return p.x() + p.y();
                    }
                }
                """;

        static final String VALIDATOR_WITH_SHARED_TYPE = """
                import java.math.BigInteger;

                @Validator
                class PointValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        Point p = new Point(3, 4);
                        return PointUtils.distance(p) == 7;
                    }
                }
                """;

        @Test
        void compilesWithSharedRecordType() {
            var program = compile(VALIDATOR_WITH_SHARED_TYPE, TYPES_LIB);
            assertNotNull(program);
        }

        @Test
        void evaluatesWithSharedRecordType() {
            var program = compile(VALIDATOR_WITH_SHARED_TYPE, TYPES_LIB);
            var ctx = mockCtx(PlutusData.integer(0));
            assertSuccess(evaluate(program, ctx));
        }
    }

    // --- Error handling ---

    @Nested
    class ErrorHandling {

        @Test
        void rejectsLibraryWithValidatorAnnotation() {
            var badLib = """
                    import java.math.BigInteger;

                    @Validator
                    class FakeValidator {
                        @Entrypoint
                        static boolean validate(BigInteger r, BigInteger c) { return true; }
                    }
                    """;
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class MyValidator {
                        @Entrypoint
                        static boolean validate(BigInteger r, BigInteger c) { return true; }
                    }
                    """;

            assertThrows(CompilerException.class,
                    () -> compiler.compile(validator, List.of(badLib)));
        }

        @Test
        void rejectsLibraryWithMintingPolicyAnnotation() {
            var badLib = """
                    import java.math.BigInteger;

                    @MintingPolicy
                    class FakePolicy {
                        @Entrypoint
                        static boolean validate(BigInteger r, BigInteger c) { return true; }
                    }
                    """;
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class MyValidator {
                        @Entrypoint
                        static boolean validate(BigInteger r, BigInteger c) { return true; }
                    }
                    """;

            assertThrows(CompilerException.class,
                    () -> compiler.compile(validator, List.of(badLib)));
        }

        @Test
        void rejectsDuplicateRecordTypes() {
            var lib = """
                    import java.math.BigInteger;

                    record Datum(BigInteger value) {}

                    class LibUtils {
                        static BigInteger extract(Datum d) { return d.value(); }
                    }
                    """;
            var validator = """
                    import java.math.BigInteger;

                    record Datum(BigInteger value) {}

                    @Validator
                    class DupValidator {
                        @Entrypoint
                        static boolean validate(BigInteger r, BigInteger c) { return true; }
                    }
                    """;

            assertThrows(CompilerException.class,
                    () -> compiler.compile(validator, List.of(lib)));
        }
    }

    // --- Backward compatibility ---

    @Nested
    class BackwardCompatibility {

        @Test
        void singleFileCompileStillWorks() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class SimpleValidator {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return redeemer == 42;
                        }
                    }
                    """;
            var result = compiler.compile(source);
            assertNotNull(result.program());
        }

        @Test
        void emptyLibraryListSameAsSingleFile() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class SimpleValidator {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return redeemer == 42;
                        }
                    }
                    """;
            var result1 = compiler.compile(source);
            var result2 = compiler.compile(source, List.of());

            assertNotNull(result1.program());
            assertNotNull(result2.program());
        }
    }

    // --- CompileWithSiblings and Path overloads ---

    @Nested
    class PathOverloads {

        @Test
        void compileWithPathOverload() throws Exception {
            var tmpFile = java.nio.file.Files.createTempFile("TestValidator", ".java");
            java.nio.file.Files.writeString(tmpFile, """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return true;
                        }
                    }
                    """);
            try {
                var result = compiler.compile(tmpFile);
                assertNotNull(result.program());
                assertFalse(result.hasErrors());
            } finally {
                java.nio.file.Files.deleteIfExists(tmpFile);
            }
        }

        @Test
        void compileWithPathAndLibraries() throws Exception {
            var tmpValidator = java.nio.file.Files.createTempFile("Validator", ".java");
            var tmpLib = java.nio.file.Files.createTempFile("MyLib", ".java");

            java.nio.file.Files.writeString(tmpValidator, """
                    import java.math.BigInteger;

                    @Validator
                    class Validator {
                        @Entrypoint
                        static boolean validate(BigInteger r, BigInteger c) {
                            return MyLib.check(42);
                        }
                    }
                    """);
            java.nio.file.Files.writeString(tmpLib, """
                    import java.math.BigInteger;

                    class MyLib {
                        static boolean check(BigInteger x) {
                            return x > 0;
                        }
                    }
                    """);

            try {
                var result = compiler.compile(tmpValidator, List.of(tmpLib));
                assertNotNull(result.program());
                assertFalse(result.hasErrors());
            } finally {
                java.nio.file.Files.deleteIfExists(tmpValidator);
                java.nio.file.Files.deleteIfExists(tmpLib);
            }
        }
    }

    // --- Shared types across multiple validators via library ---

    @Nested
    class SharedTypesAcrossValidators {

        /** Library defining shared record types + utility methods.
         *  Both validators use the same OrderDatum record and OrderUtils class. */
        static final String SHARED_TYPES_LIB = """
                import java.math.BigInteger;

                record OrderDatum(BigInteger amount, BigInteger directionTag) {}

                class OrderUtils {
                    static boolean isBuyOrder(OrderDatum datum) {
                        return datum.directionTag() == 0;
                    }

                    static BigInteger orderAmount(OrderDatum datum) {
                        return datum.amount();
                    }
                }
                """;

        /** First validator using shared types — receives datum as redeemer. */
        static final String VALIDATOR_A = """
                import java.math.BigInteger;

                @Validator
                class OrderValidator {
                    @Entrypoint
                    static boolean validate(OrderDatum redeemer, BigInteger ctx) {
                        return OrderUtils.isBuyOrder(redeemer) && redeemer.amount() > 0;
                    }
                }
                """;

        /** Second validator using the same shared types. */
        static final String VALIDATOR_B = """
                import java.math.BigInteger;

                @Validator
                class SettlementValidator {
                    @Entrypoint
                    static boolean validate(OrderDatum redeemer, BigInteger ctx) {
                        return OrderUtils.orderAmount(redeemer) == 100
                            && !OrderUtils.isBuyOrder(redeemer);
                    }
                }
                """;

        @Test
        void compilesFirstValidatorWithSharedTypes() {
            var program = compile(VALIDATOR_A, SHARED_TYPES_LIB);
            assertNotNull(program);
        }

        @Test
        void compilesSecondValidatorWithSharedTypes() {
            var program = compile(VALIDATOR_B, SHARED_TYPES_LIB);
            assertNotNull(program);
        }

        @Test
        void evaluatesFirstValidatorWithBuyOrder() {
            var program = compile(VALIDATOR_A, SHARED_TYPES_LIB);
            // OrderDatum(100, 0) -> Constr(0, [I(100), I(0)])  — directionTag=0 means Buy
            var datum = PlutusData.constr(0, PlutusData.integer(100), PlutusData.integer(0));
            var ctx = mockCtx(datum);
            assertSuccess(evaluate(program, ctx));
        }

        @Test
        void evaluatesFirstValidatorRejectsZeroAmount() {
            var program = compile(VALIDATOR_A, SHARED_TYPES_LIB);
            var datum = PlutusData.constr(0, PlutusData.integer(0), PlutusData.integer(0));
            var ctx = mockCtx(datum);
            assertFailure(evaluate(program, ctx));
        }

        @Test
        void evaluatesSecondValidatorWithSellOrder() {
            var program = compile(VALIDATOR_B, SHARED_TYPES_LIB);
            // OrderDatum(100, 1) -> Constr(0, [I(100), I(1)])  — directionTag=1 means Sell
            var datum = PlutusData.constr(0, PlutusData.integer(100), PlutusData.integer(1));
            var ctx = mockCtx(datum);
            assertSuccess(evaluate(program, ctx));
        }

        @Test
        void evaluatesSecondValidatorRejectsBuyOrder() {
            var program = compile(VALIDATOR_B, SHARED_TYPES_LIB);
            var datum = PlutusData.constr(0, PlutusData.integer(100), PlutusData.integer(0));
            var ctx = mockCtx(datum);
            assertFailure(evaluate(program, ctx));
        }
    }

    // --- Boolean and conditional logic in libraries ---

    @Nested
    class LibraryLogic {

        static final String BOOL_LIB = """
                import java.math.BigInteger;

                class BoolUtils {
                    static boolean and_(boolean a, boolean b) {
                        if (a) { return b; } else { return false; }
                    }

                    static boolean or_(boolean a, boolean b) {
                        if (a) { return true; } else { return b; }
                    }
                }
                """;

        @Test
        void libraryBooleanLogicWorks() {
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class BoolValidator {
                        @Entrypoint
                        static boolean validate(BigInteger r, BigInteger c) {
                            return BoolUtils.and_(true, true)
                                && BoolUtils.or_(false, true);
                        }
                    }
                    """;
            var program = compile(validator, BOOL_LIB);
            var ctx = mockCtx(PlutusData.integer(0));
            assertSuccess(evaluate(program, ctx));
        }

        @Test
        void libraryBooleanLogicRejects() {
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class BoolRejectValidator {
                        @Entrypoint
                        static boolean validate(BigInteger r, BigInteger c) {
                            return BoolUtils.and_(true, false);
                        }
                    }
                    """;
            var program = compile(validator, BOOL_LIB);
            var ctx = mockCtx(PlutusData.integer(0));
            assertFailure(evaluate(program, ctx));
        }
    }

    // --- Static field initializers in libraries ---

    @Nested
    class ParamTypeCoercion {

        @Test
        void localIntegerPassedToTypedLibMethod() {
            // Local variables (not entrypoint params) are actual Integer values.
            // Library method also declares BigInteger (IntegerType) — no coercion needed.
            var libSource = """
                    import java.math.BigInteger;

                    @OnchainLibrary
                    class TypedLib {
                        static boolean isAboveThreshold(BigInteger x) {
                            return x > 10;
                        }
                    }
                    """;
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            BigInteger val = 20;
                            return TypedLib.isAboveThreshold(val);
                        }
                    }
                    """;
            var program = compile(validator, libSource);
            var ctx = mockCtx(PlutusData.integer(0));
            assertSuccess(evaluate(program, ctx));
        }

        @Test
        void dataEntrypointParamPassedToDataLibMethod() {
            // Both sides PlutusData (DataType) — no coercion needed.
            // This is the baseline: Data→Data should work without coercion.
            var libSource = """
                    @OnchainLibrary
                    class DataCheckLib {
                        static boolean areSame(PlutusData a, PlutusData b) {
                            return a == b;
                        }
                    }
                    """;
            var validator = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return DataCheckLib.areSame(redeemer, redeemer);
                        }
                    }
                    """;
            var program = compile(validator, libSource);
            var ctx = mockCtx(PlutusData.integer(5));
            assertSuccess(evaluate(program, ctx));
        }

        @Test
        void dataEntrypointParamPassedToIntegerLibMethod() {
            // Validator passes PlutusData (DataType), library expects BigInteger (IntegerType).
            // The compiler should insert UnIData coercion to decode Data → Integer.
            var libSource = """
                    import java.math.BigInteger;

                    @OnchainLibrary
                    class IntLib {
                        static boolean isEven(BigInteger x) {
                            BigInteger rem = x % 2;
                            return rem == 0;
                        }
                    }
                    """;
            var validator = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return IntLib.isEven(redeemer);
                        }
                    }
                    """;
            var program = compile(validator, libSource);
            var ctx = mockCtx(PlutusData.integer(42));
            assertSuccess(evaluate(program, ctx));
        }

        @Test
        void dataEntrypointParamPassedToIntegerLibMethodFalseCase() {
            // Negative case: odd number should fail
            var libSource = """
                    import java.math.BigInteger;

                    @OnchainLibrary
                    class IntLib2 {
                        static boolean isEven(BigInteger x) {
                            BigInteger rem = x % 2;
                            return rem == 0;
                        }
                    }
                    """;
            var validator = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return IntLib2.isEven(redeemer);
                        }
                    }
                    """;
            var program = compile(validator, libSource);
            var ctx = mockCtx(PlutusData.integer(7));
            assertFailure(evaluate(program, ctx));
        }
    }

    @Nested
    class SealedInterfaceInLibrary {

        @Test
        void sealedInterfaceInLibraryPatternMatch() {
            // Sealed interface + records defined in library, switch expression in validator.
            var libSource = """
                    import java.math.BigInteger;

                    sealed interface Action permits Mint, Burn {}
                    record Mint(BigInteger amount) implements Action {}
                    record Burn(BigInteger amount) implements Action {}
                    """;
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class TokenValidator {
                        @Entrypoint
                        static boolean validate(Action redeemer, PlutusData ctx) {
                            BigInteger amt = switch (redeemer) {
                                case Mint m -> m.amount();
                                case Burn b -> b.amount();
                            };
                            return amt > 0;
                        }
                    }
                    """;
            var program = compile(validator, libSource);
            // Mint(10) = Constr(0, [IData(10)])
            var mintRedeemer = PlutusData.constr(0, PlutusData.integer(10));
            var ctx = mockCtx(mintRedeemer);
            assertSuccess(evaluate(program, ctx));
        }

        @Test
        void sealedInterfaceInLibraryBurnCase() {
            var libSource = """
                    import java.math.BigInteger;

                    sealed interface Action2 permits Mint2, Burn2 {}
                    record Mint2(BigInteger amount) implements Action2 {}
                    record Burn2(BigInteger amount) implements Action2 {}
                    """;
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class TokenValidator2 {
                        @Entrypoint
                        static boolean validate(Action2 redeemer, PlutusData ctx) {
                            boolean isMint = switch (redeemer) {
                                case Mint2 m -> true;
                                case Burn2 b -> false;
                            };
                            return isMint;
                        }
                    }
                    """;
            var program = compile(validator, libSource);
            // Burn2 = Constr(1, [IData(5)]) — should return false (validator fails)
            var burnRedeemer = PlutusData.constr(1, PlutusData.integer(5));
            var ctx = mockCtx(burnRedeemer);
            assertFailure(evaluate(program, ctx));
        }

        @Test
        void sealedInterfaceAsRecordFieldType() {
            // Record has a sealed interface field type, both in library.
            var libSource = """
                    import java.math.BigInteger;

                    sealed interface Direction permits Buy, Sell {}
                    record Buy() implements Direction {}
                    record Sell() implements Direction {}
                    record Order(BigInteger price, Direction dir) {}
                    """;
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class OrderValidator {
                        @Entrypoint
                        static boolean validate(Order redeemer, PlutusData ctx) {
                            BigInteger p = redeemer.price();
                            Direction d = redeemer.dir();
                            boolean isBuy = switch (d) {
                                case Buy b -> true;
                                case Sell s -> false;
                            };
                            return p > 0 && isBuy;
                        }
                    }
                    """;
            var program = compile(validator, libSource);
            // Order(100, Buy()) = Constr(0, [IData(100), Constr(0, [])])
            var order = PlutusData.constr(0, PlutusData.integer(100), PlutusData.constr(0));
            var ctx = mockCtx(order);
            assertSuccess(evaluate(program, ctx));
        }

        @Test
        void recordInLibraryUsedAsParam() {
            // Record defined in library, used as method param type in validator.
            var libSource = """
                    import java.math.BigInteger;

                    record Config(BigInteger minAmount, BigInteger maxAmount) {}
                    """;
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class ConfigValidator {
                        @Entrypoint
                        static boolean validate(Config redeemer, PlutusData ctx) {
                            BigInteger min = redeemer.minAmount();
                            BigInteger max = redeemer.maxAmount();
                            return min < max;
                        }
                    }
                    """;
            var program = compile(validator, libSource);
            // Config(10, 100) = Constr(0, [IData(10), IData(100)])
            var configRedeemer = PlutusData.constr(0, PlutusData.integer(10), PlutusData.integer(100));
            var ctx = mockCtx(configRedeemer);
            assertSuccess(evaluate(program, ctx));
        }

        @Test
        void crossLibrarySealedInterface() {
            // Sealed interface in LibA, utility method in LibB uses it, validator calls LibB.
            var libA = """
                    import java.math.BigInteger;

                    sealed interface TokenAction permits MintToken, BurnToken {}
                    record MintToken(BigInteger qty) implements TokenAction {}
                    record BurnToken(BigInteger qty) implements TokenAction {}
                    """;
            var libB = """
                    import java.math.BigInteger;

                    class TokenUtils {
                        static BigInteger getQuantity(TokenAction action) {
                            return switch (action) {
                                case MintToken m -> m.qty();
                                case BurnToken b -> b.qty();
                            };
                        }
                    }
                    """;
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class TokenValidator {
                        @Entrypoint
                        static boolean validate(TokenAction redeemer, PlutusData ctx) {
                            BigInteger qty = TokenUtils.getQuantity(redeemer);
                            return qty > 0;
                        }
                    }
                    """;
            var program = compile(validator, libA, libB);
            // MintToken(50) = Constr(0, [IData(50)])
            var mintRedeemer = PlutusData.constr(0, PlutusData.integer(50));
            var ctx = mockCtx(mintRedeemer);
            assertSuccess(evaluate(program, ctx));
        }
    }

    @Nested
    class StaticFieldInLibrary {

        private static final String LIB_WITH_STATIC = """
                import java.math.BigInteger;

                @OnchainLibrary
                class ConfigLib {
                    static BigInteger THRESHOLD = BigInteger.valueOf(42);

                    static boolean isAboveThreshold(BigInteger x) {
                        return x > THRESHOLD;
                    }
                }
                """;

        @Test
        void libraryStaticFieldCompiles() {
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return ConfigLib.isAboveThreshold(redeemer);
                        }
                    }
                    """;
            // Verify compilation succeeds (the static field in library is compiled correctly)
            var program = compile(validator, LIB_WITH_STATIC);
            assertNotNull(program);
        }

        @Test
        void libraryStaticFieldEvaluates() {
            // Library method uses its own static field internally — no cross-boundary type issues
            var libSource = """
                    import java.math.BigInteger;

                    @OnchainLibrary
                    class ConfigLib2 {
                        static BigInteger ANSWER = BigInteger.valueOf(42);

                        static BigInteger getAnswer() {
                            return ANSWER;
                        }

                        static BigInteger addToAnswer(BigInteger x) {
                            return x + ANSWER;
                        }
                    }
                    """;
            var validator = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            var answer = ConfigLib2.getAnswer();
                            return answer == 42;
                        }
                    }
                    """;
            var program = compile(validator, libSource);
            var ctx = mockCtx(PlutusData.integer(0));
            assertSuccess(evaluate(program, ctx));
        }
    }
}
