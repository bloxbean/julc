package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for variadic compiler intrinsics: {@code Builtins.concat(...)} and
 * {@code Builtins.listData(JulcList.of(...))}.
 * <p>
 * These are PIR-registered (StdlibRegistry), so on-chain behavior is tested through
 * compiled source via {@link JulcEval#forSource}, and JVM parity through direct calls.
 */
class VariadicIntrinsicsTest {

    private static final String IMPORTS = """
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            import com.bloxbean.cardano.julc.core.PlutusData;
            import com.bloxbean.cardano.julc.core.types.JulcList;
            import java.math.BigInteger;
            """;

    @Nested
    class ConcatOnChain {

        @Test
        void concatTwoParts() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static byte[] test(byte[] a, byte[] b) {
                            return Builtins.concat(a, b);
                        }
                    }
                    """);
            byte[] result = eval.call("test", new byte[]{1, 2}, new byte[]{3, 4}).asByteString();
            assertArrayEquals(new byte[]{1, 2, 3, 4}, result);
        }

        @Test
        void concatThreeParts() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static byte[] test(byte[] a, byte[] b, byte[] c) {
                            return Builtins.concat(a, b, c);
                        }
                    }
                    """);
            byte[] result = eval.call("test",
                    new byte[]{1, 2}, new byte[]{3, 4}, new byte[]{5, 6}).asByteString();
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, result);
        }

        @Test
        void concatFiveParts() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static byte[] test(byte[] a, byte[] b, byte[] c, byte[] d, byte[] e) {
                            return Builtins.concat(a, b, c, d, e);
                        }
                    }
                    """);
            byte[] result = eval.call("test",
                    new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[]{4}, new byte[]{5}).asByteString();
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, result);
        }

        @Test
        void concatWithEmptyParts() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static byte[] test(byte[] a, byte[] b, byte[] c) {
                            return Builtins.concat(a, b, c);
                        }
                    }
                    """);
            byte[] result = eval.call("test",
                    new byte[]{}, new byte[]{7}, new byte[]{}).asByteString();
            assertArrayEquals(new byte[]{7}, result);
        }

        @Test
        void concatMatchesNestedAppendByteString() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(byte[] a, byte[] b, byte[] c) {
                            byte[] viaConcat = Builtins.concat(a, b, c);
                            byte[] viaAppend = Builtins.appendByteString(Builtins.appendByteString(a, b), c);
                            return Builtins.equalsByteString(viaConcat, viaAppend);
                        }
                    }
                    """);
            assertTrue(eval.call("test",
                    new byte[]{1, 2}, new byte[]{3}, new byte[]{4, 5}).asBoolean());
        }

        @Test
        void concatRejectsZeroPartsInSourceCompilation() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static byte[] test() {
                            return Builtins.concat();
                        }
                    }
                    """);

            assertThrows(IllegalArgumentException.class, () -> eval.call("test"));
        }

        @Test
        void concatRejectsOnePartInSourceCompilation() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static byte[] test(byte[] only) {
                            return Builtins.concat(only);
                        }
                    }
                    """);

            assertThrows(IllegalArgumentException.class,
                    () -> eval.call("test", new byte[]{1}));
        }
    }

    @Nested
    class ConcatJvm {

        @Test
        void jvmConcatMatchesOnChain() {
            byte[] a = {1, 2}, b = {3}, c = {4, 5};
            byte[] jvm = Builtins.concat(a, b, c);
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, jvm);
        }

        @Test
        void jvmConcatTwoArgs() {
            assertArrayEquals(new byte[]{1, 2, 3, 4},
                    Builtins.concat(new byte[]{1, 2}, new byte[]{3, 4}));
        }

        @Test
        void jvmConcatAllEmpty() {
            assertArrayEquals(new byte[]{},
                    Builtins.concat(new byte[]{}, new byte[]{}, new byte[]{}));
        }
    }

    @Nested
    class ListDataOfJulcList {

        @Test
        void listDataOfPlutusDataElements() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(PlutusData expected) {
                            PlutusData built = Builtins.listData(JulcList.of(
                                    Builtins.iData(BigInteger.valueOf(1)),
                                    Builtins.iData(BigInteger.valueOf(2))));
                            return Builtins.equalsData(built, expected);
                        }
                    }
                    """);
            var expected = new PlutusData.ListData(List.of(
                    new PlutusData.IntData(BigInteger.ONE),
                    new PlutusData.IntData(BigInteger.TWO)));
            assertTrue(eval.call("test", expected).asBoolean());
        }

        @Test
        void listDataOfAutoWrappedIntegers() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(PlutusData expected) {
                            BigInteger pkh = BigInteger.valueOf(11);
                            BigInteger recipient = BigInteger.valueOf(22);
                            PlutusData built = Builtins.listData(JulcList.of(pkh, recipient));
                            return Builtins.equalsData(built, expected);
                        }
                    }
                    """);
            var expected = new PlutusData.ListData(List.of(
                    new PlutusData.IntData(BigInteger.valueOf(11)),
                    new PlutusData.IntData(BigInteger.valueOf(22))));
            assertTrue(eval.call("test", expected).asBoolean());
        }

        @Test
        void jvmListDataMatchesOnChainWrapping() {
            var list = com.bloxbean.cardano.julc.core.types.JulcList.of(
                    BigInteger.valueOf(11), BigInteger.valueOf(22));
            var jvm = Builtins.listData(list);
            var expected = new PlutusData.ListData(List.of(
                    new PlutusData.IntData(BigInteger.valueOf(11)),
                    new PlutusData.IntData(BigInteger.valueOf(22))));
            assertEquals(expected, jvm);
        }

        @Test
        void jvmListDataMixedElements() {
            var list = com.bloxbean.cardano.julc.core.types.JulcList.of(
                    (Object) BigInteger.ONE, new byte[]{1, 2}, true);
            var jvm = Builtins.listData(list);
            assertEquals(3, jvm.items().size());
            assertEquals(new PlutusData.IntData(BigInteger.ONE), jvm.items().get(0));
            assertEquals(new PlutusData.BytesData(new byte[]{1, 2}), jvm.items().get(1));
            assertEquals(new PlutusData.ConstrData(1, List.of()), jvm.items().get(2));
        }

        @Test
        void jvmListDataUnsupportedElementThrows() {
            var list = com.bloxbean.cardano.julc.core.types.JulcList.of(new Object());
            assertThrows(IllegalArgumentException.class, () -> Builtins.listData(list));
        }

        @Test
        void chainedJulcListToPlutusDataCompilesAndEvaluates() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(PlutusData expected) {
                            PlutusData actual = JulcList.of(
                                    BigInteger.valueOf(11),
                                    BigInteger.valueOf(22)).toPlutusData();
                            return Builtins.equalsData(actual, expected);
                        }
                    }
                    """);
            var expected = PlutusData.list(
                    PlutusData.integer(11),
                    PlutusData.integer(22));

            assertTrue(eval.call("test", expected).asBoolean());
        }

        @Test
        void variableJulcListToPlutusDataCompilesAndEvaluates() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(PlutusData expected) {
                            JulcList<BigInteger> values =
                                    JulcList.of(BigInteger.ONE, BigInteger.TWO);
                            PlutusData actual = values.toPlutusData();
                            return Builtins.equalsData(actual, expected);
                        }
                    }
                    """);
            var expected = PlutusData.list(
                    PlutusData.integer(1),
                    PlutusData.integer(2));

            assertTrue(eval.call("test", expected).asBoolean());
        }
    }

    @Nested
    class MapToPlutusData {

        @Test
        void variableJulcMapToPlutusDataCompilesAndEvaluates() {
            var eval = JulcEval.forSource(IMPORTS + """
                    import com.bloxbean.cardano.julc.core.types.JulcMap;
                    class T {
                        static boolean test(PlutusData expected) {
                            JulcMap<BigInteger, byte[]> empty = JulcMap.empty();
                            JulcMap<BigInteger, byte[]> values =
                                    empty.insert(BigInteger.valueOf(7), new byte[]{1, 2});
                            PlutusData actual = values.toPlutusData();
                            return Builtins.equalsData(actual, expected);
                        }
                    }
                    """);
            var expected = PlutusData.map(new PlutusData.Pair(
                    PlutusData.integer(7),
                    PlutusData.bytes(new byte[]{1, 2})));

            assertTrue(eval.call("test", expected).asBoolean());
        }
    }
}
