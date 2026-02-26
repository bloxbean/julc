package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.testkit.fixtures.SampleValidator;
import com.bloxbean.cardano.julc.vm.TermExtractor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JulcEval — type-safe evaluator API for MethodEvaluator.
 */
class JulcEvalTest {

    static final Path TEST_SOURCE_ROOT = Path.of("src/test/java");

    // -------------------------------------------------------------------------
    // Interface proxy with file-based class
    // -------------------------------------------------------------------------

    interface SampleValidatorProxy {
        BigInteger doubleIt(long x);
        byte[] concatBytes(byte[] a, byte[] b);
        boolean isPositive(long x);
        BigInteger sumUpTo(long n);
    }

    @Nested
    class InterfaceProxyFileBased {

        final SampleValidatorProxy proxy = JulcEval
                .forClass(SampleValidator.class, TEST_SOURCE_ROOT)
                .create(SampleValidatorProxy.class);

        @Test
        void doubleIt() {
            assertEquals(BigInteger.valueOf(42), proxy.doubleIt(21));
        }

        @Test
        void doubleItZero() {
            assertEquals(BigInteger.ZERO, proxy.doubleIt(0));
        }

        @Test
        void concatBytes() {
            assertArrayEquals(
                    new byte[]{1, 2, 3, 4, 5},
                    proxy.concatBytes(new byte[]{1, 2, 3}, new byte[]{4, 5}));
        }

        @Test
        void isPositiveTrue() {
            assertTrue(proxy.isPositive(5));
        }

        @Test
        void isPositiveFalse() {
            assertFalse(proxy.isPositive(-1));
        }

        @Test
        void sumUpTo() {
            assertEquals(BigInteger.valueOf(15), proxy.sumUpTo(5));
        }
    }

    // -------------------------------------------------------------------------
    // Interface proxy with inline source
    // -------------------------------------------------------------------------

    static final String MATH_SOURCE = """
            class MathUtils {
                static BigInteger doubleIt(BigInteger x) { return x * 2; }
                static BigInteger add(BigInteger x, BigInteger y) { return x + y; }
                static boolean isEven(BigInteger x) { return x % 2 == 0; }
                static BigInteger theAnswer() { return 42; }
            }
            """;

    interface MathProxy {
        BigInteger doubleIt(long x);
        BigInteger add(long x, long y);
        boolean isEven(long x);
        BigInteger theAnswer();
    }

    @Nested
    class InterfaceProxyInlineSource {

        final MathProxy proxy = JulcEval.forSource(MATH_SOURCE)
                .create(MathProxy.class);

        @Test
        void doubleIt() {
            assertEquals(BigInteger.valueOf(42), proxy.doubleIt(21));
        }

        @Test
        void addTwoNumbers() {
            assertEquals(BigInteger.valueOf(42), proxy.add(10, 32));
        }

        @Test
        void isEvenTrue() {
            assertTrue(proxy.isEven(4));
        }

        @Test
        void isEvenFalse() {
            assertFalse(proxy.isEven(7));
        }

        @Test
        void zeroArgMethod() {
            assertEquals(BigInteger.valueOf(42), proxy.theAnswer());
        }
    }

    // -------------------------------------------------------------------------
    // All argument types
    // -------------------------------------------------------------------------

    @Nested
    class ArgTypes {

        static final String SOURCE = """
                class ArgTest {
                    static BigInteger identity(BigInteger x) { return x; }
                    static boolean not(boolean b) { return !b; }
                }
                """;

        @Test
        void bigIntegerArg() {
            var proxy = JulcEval.forSource(SOURCE);
            assertEquals(BigInteger.valueOf(99),
                    proxy.call("identity", BigInteger.valueOf(99)).asInteger());
        }

        @Test
        void intArg() {
            var proxy = JulcEval.forSource(SOURCE);
            assertEquals(BigInteger.valueOf(42),
                    proxy.call("identity", 42).asInteger());
        }

        @Test
        void longArg() {
            var proxy = JulcEval.forSource(SOURCE);
            assertEquals(BigInteger.valueOf(100L),
                    proxy.call("identity", 100L).asInteger());
        }

        @Test
        void booleanArg() {
            var proxy = JulcEval.forSource(SOURCE);
            assertFalse(proxy.call("not", true).asBoolean());
            assertTrue(proxy.call("not", false).asBoolean());
        }

        @Test
        void byteArrayArg() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    class ByteTest {
                        static byte[] echo(byte[] bs) { return bs; }
                    }
                    """;
            var proxy = JulcEval.forSource(source);
            assertArrayEquals(new byte[]{1, 2, 3},
                    proxy.call("echo", new byte[]{1, 2, 3}).asByteString());
        }
    }

    // -------------------------------------------------------------------------
    // All return types
    // -------------------------------------------------------------------------

    @Nested
    class ReturnTypes {

        static final String SOURCE = """
                class ReturnTest {
                    static BigInteger getInt() { return 42; }
                    static boolean getBool() { return true; }
                }
                """;

        interface ReturnProxy {
            BigInteger getInt();
            boolean getBool();
        }

        @Test
        void bigIntegerReturn() {
            var proxy = JulcEval.forSource(SOURCE).create(ReturnProxy.class);
            assertEquals(BigInteger.valueOf(42), proxy.getInt());
        }

        @Test
        void booleanReturn() {
            var proxy = JulcEval.forSource(SOURCE).create(ReturnProxy.class);
            assertTrue(proxy.getBool());
        }

        @Test
        void longReturnViaProxy() {
            interface LongProxy {
                long getInt();
            }
            var proxy = JulcEval.forSource(SOURCE).create(LongProxy.class);
            assertEquals(42L, proxy.getInt());
        }

        @Test
        void intReturnViaProxy() {
            interface IntProxy {
                int getInt();
            }
            var proxy = JulcEval.forSource(SOURCE).create(IntProxy.class);
            assertEquals(42, proxy.getInt());
        }

        @Test
        void byteArrayReturnViaProxy() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    class ByteRetTest {
                        static byte[] echo(byte[] bs) { return bs; }
                    }
                    """;
            interface ByteProxy {
                byte[] echo(byte[] bs);
            }
            var proxy = JulcEval.forSource(source).create(ByteProxy.class);
            assertArrayEquals(new byte[]{10, 20}, proxy.echo(new byte[]{10, 20}));
        }
    }

    // -------------------------------------------------------------------------
    // Ledger record return type (auto-decode via reflection)
    // -------------------------------------------------------------------------

    @Nested
    class LedgerTypeReturn {

        @Test
        void pubKeyHashReturn() {
            // PubKeyHash is a 28-byte hash; on-chain it's just BytesData
            var source = """
                    class HashTest {
                        static byte[] makeHash(byte[] bs) { return bs; }
                    }
                    """;
            // Use CallResult.as(PubKeyHash.class) to auto-decode
            var hash = new byte[28];
            for (int i = 0; i < 28; i++) hash[i] = (byte) (i + 1);

            var result = JulcEval.forSource(source)
                    .call("makeHash", hash)
                    .as(PubKeyHash.class);

            assertInstanceOf(PubKeyHash.class, result);
            assertArrayEquals(hash, result.hash());
        }
    }

    // -------------------------------------------------------------------------
    // Fluent call API
    // -------------------------------------------------------------------------

    @Nested
    class FluentCallApi {

        @Test
        void callAsInteger() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            assertEquals(BigInteger.valueOf(42), eval.call("doubleIt", 21).asInteger());
        }

        @Test
        void callAsLong() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            assertEquals(42L, eval.call("doubleIt", 21).asLong());
        }

        @Test
        void callAsInt() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            assertEquals(42, eval.call("doubleIt", 21).asInt());
        }

        @Test
        void callAsBoolean() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            assertTrue(eval.call("isEven", 4).asBoolean());
        }

        @Test
        void callAuto() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            Object result = eval.call("doubleIt", 21).auto();
            assertInstanceOf(BigInteger.class, result);
            assertEquals(BigInteger.valueOf(42), result);
        }

        @Test
        void callRawTerm() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            var term = eval.call("doubleIt", 21).rawTerm();
            assertNotNull(term);
        }

        @Test
        void callAsData() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            PlutusData data = eval.call("doubleIt", 21).asData();
            assertInstanceOf(PlutusData.IntData.class, data);
            assertEquals(BigInteger.valueOf(42), ((PlutusData.IntData) data).value());
        }

        @Test
        void callFileBasedFluent() {
            var eval = JulcEval.forClass(SampleValidator.class, TEST_SOURCE_ROOT);
            assertEquals(BigInteger.valueOf(42), eval.call("doubleIt", 21).asInteger());
        }

        @Test
        void callZeroArgs() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            assertEquals(BigInteger.valueOf(42), eval.call("theAnswer").asInteger());
        }

        @Test
        void callMultiArgs() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            assertEquals(BigInteger.valueOf(42), eval.call("add", 10, 32).asInteger());
        }
    }

    // -------------------------------------------------------------------------
    // Object methods on proxy
    // -------------------------------------------------------------------------

    @Nested
    class ObjectMethodsOnProxy {

        @Test
        void toStringDoesNotRouteToUplc() {
            var proxy = JulcEval.forSource(MATH_SOURCE).create(MathProxy.class);
            String s = proxy.toString();
            assertNotNull(s);
            assertTrue(s.startsWith("JulcEval$"));
        }

        @Test
        void hashCodeReturnsIdentity() {
            var proxy = JulcEval.forSource(MATH_SOURCE).create(MathProxy.class);
            // Should not throw
            int h = proxy.hashCode();
            assertEquals(System.identityHashCode(proxy), h);
        }

        @Test
        void equalsSameInstance() {
            var proxy = JulcEval.forSource(MATH_SOURCE).create(MathProxy.class);
            assertTrue(proxy.equals(proxy));
        }

        @Test
        void equalsDifferentInstance() {
            var proxy1 = JulcEval.forSource(MATH_SOURCE).create(MathProxy.class);
            var proxy2 = JulcEval.forSource(MATH_SOURCE).create(MathProxy.class);
            assertFalse(proxy1.equals(proxy2));
        }
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    @Nested
    class ErrorCases {

        @Test
        void createWithNonInterface() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            assertThrows(IllegalArgumentException.class, () -> eval.create(String.class));
        }

        @Test
        void unsupportedArgType() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            assertThrows(IllegalArgumentException.class,
                    () -> eval.call("doubleIt", new Object()));
        }

        @Test
        void nullSource() {
            assertThrows(NullPointerException.class,
                    () -> JulcEval.forSource(null));
        }

        @Test
        void nullClass() {
            assertThrows(NullPointerException.class,
                    () -> JulcEval.forClass(null));
        }

        @Test
        void nullProxyInterface() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            assertThrows(NullPointerException.class, () -> eval.create(null));
        }

        @Test
        void methodNotFound() {
            var eval = JulcEval.forSource(MATH_SOURCE);
            assertThrows(Exception.class, () -> eval.call("nonExistent"));
        }
    }

    // -------------------------------------------------------------------------
    // ArgConverter unit tests
    // -------------------------------------------------------------------------

    @Nested
    class ArgConverterTests {

        @Test
        void convertNullArray() {
            assertArrayEquals(new PlutusData[0], ArgConverter.convert(null));
        }

        @Test
        void convertEmptyArray() {
            assertArrayEquals(new PlutusData[0], ArgConverter.convert(new Object[0]));
        }

        @Test
        void convertPlutusDataPassthrough() {
            var pd = PlutusData.integer(42);
            var result = ArgConverter.convert(new Object[]{pd});
            assertEquals(1, result.length);
            assertSame(pd, result[0]);
        }

        @Test
        void convertNullElement() {
            assertThrows(IllegalArgumentException.class,
                    () -> ArgConverter.convert(new Object[]{null}));
        }

        @Test
        void convertPubKeyHash() {
            var hash = new byte[28];
            var pkh = PubKeyHash.of(hash);
            var result = ArgConverter.convert(new Object[]{pkh});
            assertEquals(1, result.length);
            assertInstanceOf(PlutusData.BytesData.class, result[0]);
        }
    }

    // -------------------------------------------------------------------------
    // ResultConverter unit tests
    // -------------------------------------------------------------------------

    @Nested
    class ResultConverterTests {

        @Test
        void convertVoidReturnsNull() {
            // Use any term — should return null for void
            var term = MethodEvaluator.evaluateTerm(MATH_SOURCE, "doubleIt",
                    PlutusData.integer(1));
            assertNull(ResultConverter.convert(term, void.class));
            assertNull(ResultConverter.convert(term, Void.class));
        }

        @Test
        void convertObject() {
            var term = MethodEvaluator.evaluateTerm(MATH_SOURCE, "doubleIt",
                    PlutusData.integer(21));
            Object result = ResultConverter.convert(term, Object.class);
            assertInstanceOf(BigInteger.class, result);
            assertEquals(BigInteger.valueOf(42), result);
        }

        @Test
        void unsupportedReturnType() {
            var term = MethodEvaluator.evaluateTerm(MATH_SOURCE, "doubleIt",
                    PlutusData.integer(1));
            assertThrows(TermExtractor.ExtractionException.class,
                    () -> ResultConverter.convert(term, Thread.class));
        }
    }
}
