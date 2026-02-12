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
}
