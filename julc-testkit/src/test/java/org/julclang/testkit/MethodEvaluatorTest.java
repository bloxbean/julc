package org.julclang.testkit;

import org.julclang.core.PlutusData;
import org.julclang.testkit.fixtures.SampleValidator;
import org.julclang.vm.EvalResult;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MethodEvaluator — compiles individual static methods to UPLC and evaluates them.
 */
class MethodEvaluatorTest {

    // -------------------------------------------------------------------------
    // Integer arithmetic
    // -------------------------------------------------------------------------

    @Nested
    class IntegerArithmetic {

        static final String SOURCE = """
                import java.math.BigInteger;
                class MathTest {
                    static BigInteger doubleIt(BigInteger x) {
                        return x.multiply(BigInteger.TWO);
                    }

                    static BigInteger add(BigInteger x, BigInteger y) {
                        return x.add(y);
                    }

                    static BigInteger negate(BigInteger x) {
                        return x.negate();
                    }

                    static BigInteger factorial(BigInteger n) {
                        BigInteger result = BigInteger.ONE;
                        BigInteger i = BigInteger.ONE;
                        while (i.compareTo(n) <= 0) {
                            result = result.multiply(i);
                            i = i.add(BigInteger.ONE);
                        }
                        return result;
                    }
                }
                """;

        @Test
        void doubleIt() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "doubleIt",
                    PlutusData.integer(21));
            assertEquals(BigInteger.valueOf(42), result);
        }

        @Test
        void doubleItZero() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "doubleIt",
                    PlutusData.integer(0));
            assertEquals(BigInteger.ZERO, result);
        }

        @Test
        void addTwoNumbers() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "add",
                    PlutusData.integer(10), PlutusData.integer(32));
            assertEquals(BigInteger.valueOf(42), result);
        }

        @Test
        void negatePositive() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "negate",
                    PlutusData.integer(5));
            assertEquals(BigInteger.valueOf(-5), result);
        }

        @Test
        void factorial() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "factorial",
                    PlutusData.integer(5));
            assertEquals(BigInteger.valueOf(120), result);
        }

        @Test
        void factorialZero() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "factorial",
                    PlutusData.integer(0));
            assertEquals(BigInteger.ONE, result);
        }
    }

    // -------------------------------------------------------------------------
    // Boolean operations
    // -------------------------------------------------------------------------

    @Nested
    class BooleanOps {

        static final String SOURCE = """
                import java.math.BigInteger;
                class BoolTest {
                    static boolean isPositive(BigInteger x) {
                        return x.compareTo(BigInteger.ZERO) > 0;
                    }

                    static boolean isEqual(BigInteger x, BigInteger y) {
                        return x == y;
                    }

                    static boolean not(boolean b) {
                        return !b;
                    }
                }
                """;

        @Test
        void isPositiveTrue() {
            assertTrue(MethodEvaluator.evaluateBoolean(SOURCE, "isPositive",
                    PlutusData.integer(42)));
        }

        @Test
        void isPositiveFalse() {
            assertFalse(MethodEvaluator.evaluateBoolean(SOURCE, "isPositive",
                    PlutusData.integer(-1)));
        }

        @Test
        void isEqualTrue() {
            assertTrue(MethodEvaluator.evaluateBoolean(SOURCE, "isEqual",
                    PlutusData.integer(7), PlutusData.integer(7)));
        }

        @Test
        void isEqualFalse() {
            assertFalse(MethodEvaluator.evaluateBoolean(SOURCE, "isEqual",
                    PlutusData.integer(1), PlutusData.integer(2)));
        }

        @Test
        void notTrue() {
            assertFalse(MethodEvaluator.evaluateBoolean(SOURCE, "not",
                    PlutusData.constr(1))); // true = Constr(1,[])
        }

        @Test
        void notFalse() {
            assertTrue(MethodEvaluator.evaluateBoolean(SOURCE, "not",
                    PlutusData.constr(0))); // false = Constr(0,[])
        }
    }

    // -------------------------------------------------------------------------
    // Byte string operations
    // -------------------------------------------------------------------------

    @Nested
    class ByteStringOps {

        static final String SOURCE = """
                import org.julclang.stdlib.Builtins;
                import java.math.BigInteger;

                class BytesTest {
                    static byte[] concat(byte[] a, byte[] b) {
                        return Builtins.appendByteString(a, b);
                    }

                    static BigInteger len(byte[] bs) {
                        return BigInteger.valueOf(Builtins.lengthOfByteString(bs));
                    }
                }
                """;

        @Test
        void concatByteStrings() {
            var a = new byte[]{1, 2, 3};
            var b = new byte[]{4, 5};
            var result = MethodEvaluator.evaluateByteString(SOURCE, "concat",
                    PlutusData.bytes(a), PlutusData.bytes(b));
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, result);
        }

        @Test
        void lengthOfByteString() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "len",
                    PlutusData.bytes(new byte[]{10, 20, 30}));
            assertEquals(BigInteger.valueOf(3), result);
        }
    }

    // -------------------------------------------------------------------------
    // Helper method calls (inter-method calls within same class)
    // -------------------------------------------------------------------------

    @Nested
    class HelperMethods {

        static final String SOURCE = """
                import java.math.BigInteger;
                class HelperTest {
                    static BigInteger square(BigInteger x) {
                        return x.multiply(x);
                    }

                    static BigInteger sumOfSquares(BigInteger a, BigInteger b) {
                        return square(a).add(square(b));
                    }
                }
                """;

        @Test
        void sumOfSquares() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "sumOfSquares",
                    PlutusData.integer(3), PlutusData.integer(4));
            assertEquals(BigInteger.valueOf(25), result);
        }

        @Test
        void squareDirect() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "square",
                    PlutusData.integer(7));
            assertEquals(BigInteger.valueOf(49), result);
        }
    }

    // -------------------------------------------------------------------------
    // Self-recursive helper methods
    // -------------------------------------------------------------------------

    @Nested
    class RecursiveHelpers {

        static final String SOURCE = """
                import java.math.BigInteger;
                class RecursiveTest {
                    static BigInteger factorial(BigInteger n) {
                        if (n.compareTo(BigInteger.ONE) <= 0) {
                            return BigInteger.ONE;
                        } else {
                            return n.multiply(factorial(n.subtract(BigInteger.ONE)));
                        }
                    }

                    static BigInteger fib(BigInteger n) {
                        if (n.compareTo(BigInteger.ZERO) <= 0) {
                            return BigInteger.ZERO;
                        } else if (n.equals(BigInteger.ONE)) {
                            return BigInteger.ONE;
                        } else {
                            return fib(n.subtract(BigInteger.ONE)).add(fib(n.subtract(BigInteger.TWO)));
                        }
                    }

                    static BigInteger sumList(BigInteger n) {
                        if (n.compareTo(BigInteger.ZERO) <= 0) {
                            return BigInteger.ZERO;
                        } else {
                            return n.add(sumList(n.subtract(BigInteger.ONE)));
                        }
                    }

                    static BigInteger callFactorial(BigInteger n) {
                        return factorial(n).add(BigInteger.ONE);
                    }
                }
                """;

        @Test
        void factorialRecursive() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "factorial",
                    PlutusData.integer(5));
            assertEquals(BigInteger.valueOf(120), result);
        }

        @Test
        void factorialBase() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "factorial",
                    PlutusData.integer(1));
            assertEquals(BigInteger.ONE, result);
        }

        @Test
        void fibonacci() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "fib",
                    PlutusData.integer(10));
            assertEquals(BigInteger.valueOf(55), result);
        }

        @Test
        void fibonacciZero() {
            var result = MethodEvaluator.evaluateInteger(SOURCE, "fib",
                    PlutusData.integer(0));
            assertEquals(BigInteger.ZERO, result);
        }

        @Test
        void sumListRecursive() {
            // 1+2+3+4+5 = 15
            var result = MethodEvaluator.evaluateInteger(SOURCE, "sumList",
                    PlutusData.integer(5));
            assertEquals(BigInteger.valueOf(15), result);
        }

        @Test
        void callRecursiveFromNonRecursive() {
            // factorial(5) + 1 = 121
            var result = MethodEvaluator.evaluateInteger(SOURCE, "callFactorial",
                    PlutusData.integer(5));
            assertEquals(BigInteger.valueOf(121), result);
        }
    }

    // -------------------------------------------------------------------------
    // If-then-else
    // -------------------------------------------------------------------------

    @Nested
    class Conditionals {

        static final String SOURCE = """
                import java.math.BigInteger;
                class CondTest {
                    static BigInteger abs(BigInteger x) {
                        if (x.compareTo(BigInteger.ZERO) < 0) {
                            return x.negate();
                        }
                        return x;
                    }

                    static BigInteger max(BigInteger a, BigInteger b) {
                        if (a.compareTo(b) > 0) {
                            return a;
                        } else {
                            return b;
                        }
                    }

                    static BigInteger clamp(BigInteger x, BigInteger lo, BigInteger hi) {
                        if (x.compareTo(lo) < 0) {
                            return lo;
                        } else if (x.compareTo(hi) > 0) {
                            return hi;
                        } else {
                            return x;
                        }
                    }
                }
                """;

        @Test
        void absPositive() {
            assertEquals(BigInteger.valueOf(5),
                    MethodEvaluator.evaluateInteger(SOURCE, "abs", PlutusData.integer(5)));
        }

        @Test
        void absNegative() {
            assertEquals(BigInteger.valueOf(3),
                    MethodEvaluator.evaluateInteger(SOURCE, "abs", PlutusData.integer(-3)));
        }

        @Test
        void maxFirstLarger() {
            assertEquals(BigInteger.TEN,
                    MethodEvaluator.evaluateInteger(SOURCE, "max",
                            PlutusData.integer(10), PlutusData.integer(3)));
        }

        @Test
        void maxSecondLarger() {
            assertEquals(BigInteger.valueOf(20),
                    MethodEvaluator.evaluateInteger(SOURCE, "max",
                            PlutusData.integer(5), PlutusData.integer(20)));
        }

        @Test
        void clampInRange() {
            assertEquals(BigInteger.valueOf(5),
                    MethodEvaluator.evaluateInteger(SOURCE, "clamp",
                            PlutusData.integer(5), PlutusData.integer(0), PlutusData.integer(10)));
        }

        @Test
        void clampBelowLo() {
            assertEquals(BigInteger.ZERO,
                    MethodEvaluator.evaluateInteger(SOURCE, "clamp",
                            PlutusData.integer(-5), PlutusData.integer(0), PlutusData.integer(10)));
        }

        @Test
        void clampAboveHi() {
            assertEquals(BigInteger.TEN,
                    MethodEvaluator.evaluateInteger(SOURCE, "clamp",
                            PlutusData.integer(99), PlutusData.integer(0), PlutusData.integer(10)));
        }
    }

    // -------------------------------------------------------------------------
    // No-argument methods
    // -------------------------------------------------------------------------

    @Nested
    class NoArgMethods {

        static final String SOURCE = """
                import java.math.BigInteger;
                class NoArgTest {
                    static BigInteger theAnswer() {
                        return BigInteger.valueOf(42);
                    }

                    static boolean alwaysTrue() {
                        return true;
                    }
                }
                """;

        @Test
        void theAnswer() {
            assertEquals(BigInteger.valueOf(42),
                    MethodEvaluator.evaluateInteger(SOURCE, "theAnswer"));
        }

        @Test
        void alwaysTrue() {
            assertTrue(MethodEvaluator.evaluateBoolean(SOURCE, "alwaysTrue"));
        }
    }

    // -------------------------------------------------------------------------
    // Record construction
    // -------------------------------------------------------------------------

    @Nested
    class RecordConstruction {

        static final String SOURCE = """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                record Point(BigInteger x, BigInteger y) {}

                class RecordTest {
                    static PlutusData makePoint(BigInteger x, BigInteger y) {
                        return PlutusData.cast(new Point(x, y), PlutusData.class);
                    }
                }
                """;

        @Test
        void makePoint() {
            var result = MethodEvaluator.evaluateData(SOURCE, "makePoint",
                    PlutusData.integer(3), PlutusData.integer(4));
            // A record maps to ConstrData(0, [IData(3), IData(4)])
            assertInstanceOf(PlutusData.ConstrData.class, result);
            var constr = (PlutusData.ConstrData) result;
            assertEquals(0, constr.tag());
            assertEquals(2, constr.fields().size());
        }
    }

    // -------------------------------------------------------------------------
    // Auto-detection via extract()
    // -------------------------------------------------------------------------

    @Nested
    class AutoDetection {

        static final String SOURCE = """
                import java.math.BigInteger;
                class AutoTest {
                    static BigInteger intResult() { return BigInteger.valueOf(42); }
                    static boolean boolResult() { return true; }
                }
                """;

        @Test
        void autoDetectInteger() {
            Object result = MethodEvaluator.evaluate(SOURCE, "intResult");
            assertInstanceOf(BigInteger.class, result);
            assertEquals(BigInteger.valueOf(42), result);
        }

        @Test
        void autoDetectBoolean() {
            Object result = MethodEvaluator.evaluate(SOURCE, "boolResult");
            assertInstanceOf(Boolean.class, result);
            assertEquals(true, result);
        }
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    @Nested
    class ErrorCases {

        @Test
        void methodNotFound() {
            var source = """
                    import java.math.BigInteger;
                    class Foo {
                        static BigInteger bar() { return BigInteger.ONE; }
                    }
                    """;
            assertThrows(Exception.class, () ->
                    MethodEvaluator.evaluateInteger(source, "nonExistent"));
        }

        @Test
        void extractionTypeMismatch() {
            var source = """
                    import java.math.BigInteger;
                    class Foo {
                        static BigInteger getInt() { return BigInteger.valueOf(42); }
                    }
                    """;
            assertThrows(TermExtractor.ExtractionException.class, () ->
                    MethodEvaluator.evaluateByteString(source, "getInt"));
        }
    }

    // -------------------------------------------------------------------------
    // EvalResult access (raw evaluation)
    // -------------------------------------------------------------------------

    @Nested
    class RawEvaluation {

        static final String SOURCE = """
                import java.math.BigInteger;
                class RawTest {
                    static BigInteger triple(BigInteger x) { return x.multiply(BigInteger.valueOf(3)); }
                }
                """;

        @Test
        void rawEvalReturnsSuccess() {
            var result = MethodEvaluator.evaluateRaw(SOURCE, "triple",
                    PlutusData.integer(10));
            assertTrue(result.isSuccess());
        }

        @Test
        void rawEvalHasBudget() {
            var result = MethodEvaluator.evaluateRaw(SOURCE, "triple",
                    PlutusData.integer(10));
            assertInstanceOf(EvalResult.Success.class, result);
            var success = (EvalResult.Success) result;
            assertNotNull(success.consumed());
            assertTrue(success.consumed().cpuSteps() > 0);
            assertTrue(success.consumed().memoryUnits() > 0);
        }
    }

    // -------------------------------------------------------------------------
    // File-based evaluation (Class<?> overloads)
    // -------------------------------------------------------------------------

    @Nested
    class FileBasedEvaluation {

        static final Path TEST_SOURCE_ROOT = Path.of("src/test/java");

        @Test
        void doubleItFromFile() {
            var result = MethodEvaluator.evaluateInteger(SampleValidator.class,
                    TEST_SOURCE_ROOT, "doubleIt", PlutusData.integer(21));
            assertEquals(BigInteger.valueOf(42), result);
        }

        @Test
        void concatBytesFromFile() {
            var a = new byte[]{1, 2, 3};
            var b = new byte[]{4, 5};
            var result = MethodEvaluator.evaluateByteString(SampleValidator.class,
                    TEST_SOURCE_ROOT, "concatBytes",
                    PlutusData.bytes(a), PlutusData.bytes(b));
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, result);
        }

        @Test
        void isPositiveFromFile() {
            assertTrue(MethodEvaluator.evaluateBoolean(SampleValidator.class,
                    TEST_SOURCE_ROOT, "isPositive", PlutusData.integer(42)));
            assertFalse(MethodEvaluator.evaluateBoolean(SampleValidator.class,
                    TEST_SOURCE_ROOT, "isPositive", PlutusData.integer(-1)));
        }

        @Test
        void sumUpToFromFile() {
            // 1+2+3+4+5 = 15
            var result = MethodEvaluator.evaluateInteger(SampleValidator.class,
                    TEST_SOURCE_ROOT, "sumUpTo", PlutusData.integer(5));
            assertEquals(BigInteger.valueOf(15), result);
        }

        @Test
        void rawEvalFromFile() {
            var result = MethodEvaluator.evaluateRaw(SampleValidator.class,
                    TEST_SOURCE_ROOT, "doubleIt", PlutusData.integer(10));
            assertTrue(result.isSuccess());
        }

        @Test
        void autoDetectFromFile() {
            Object result = MethodEvaluator.evaluate(SampleValidator.class,
                    TEST_SOURCE_ROOT, "doubleIt", PlutusData.integer(5));
            assertInstanceOf(BigInteger.class, result);
            assertEquals(BigInteger.TEN, result);
        }

        @Test
        void compileMethodFromFile() {
            var program = MethodEvaluator.compileMethod(SampleValidator.class,
                    TEST_SOURCE_ROOT, "doubleIt");
            assertNotNull(program);
        }
    }
}
