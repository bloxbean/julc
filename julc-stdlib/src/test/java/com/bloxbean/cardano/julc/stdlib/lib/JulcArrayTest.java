package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcArray;
import com.bloxbean.cardano.julc.core.types.JulcArrayImpl;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JulcArray} — PV11 array operations (CIP-156).
 * On-chain tests use JulcEval to compile and evaluate through the UPLC VM.
 * Off-chain tests use JulcArrayImpl directly.
 */
class JulcArrayTest {

    private static final String IMPORTS = """
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            import com.bloxbean.cardano.julc.core.PlutusData;
            import java.math.BigInteger;
            """;

    @Nested
    class OnChainTests {

        @Nested
        class FromListAndLength {

            @Test
            void emptyListProducesZeroLength() {
                // ListToArray operates on UPLC lists, unwrap Data first
                var eval = JulcEval.forSource(IMPORTS + """
                        class T {
                            static BigInteger test(PlutusData data) {
                                var list = Builtins.unListData(data);
                                var arr = Builtins.listToArray(list);
                                return BigInteger.valueOf(Builtins.lengthOfArray(arr));
                            }
                        }
                        """);
                var empty = new PlutusData.ListData(List.of());
                assertEquals(0, eval.call("test", empty).asLong());
            }

            @Test
            void singleElementLength() {
                var eval = JulcEval.forSource(IMPORTS + """
                        class T {
                            static BigInteger test(PlutusData data) {
                                var list = Builtins.unListData(data);
                                var arr = Builtins.listToArray(list);
                                return BigInteger.valueOf(Builtins.lengthOfArray(arr));
                            }
                        }
                        """);
                var list = new PlutusData.ListData(List.of(
                        new PlutusData.IntData(BigInteger.valueOf(42))));
                assertEquals(1, eval.call("test", list).asLong());
            }

            @Test
            void multipleElementLength() {
                var eval = JulcEval.forSource(IMPORTS + """
                        class T {
                            static BigInteger test(PlutusData data) {
                                var list = Builtins.unListData(data);
                                var arr = Builtins.listToArray(list);
                                return BigInteger.valueOf(Builtins.lengthOfArray(arr));
                            }
                        }
                        """);
                var list = new PlutusData.ListData(List.of(
                        new PlutusData.IntData(BigInteger.valueOf(1)),
                        new PlutusData.IntData(BigInteger.valueOf(2)),
                        new PlutusData.IntData(BigInteger.valueOf(3))));
                assertEquals(3, eval.call("test", list).asLong());
            }
        }

        @Nested
        class IndexAccess {

            @Test
            void firstElement() {
                var eval = JulcEval.forSource(IMPORTS + """
                        class T {
                            static BigInteger test(PlutusData data) {
                                var list = Builtins.unListData(data);
                                var arr = Builtins.listToArray(list);
                                return Builtins.unIData(Builtins.indexArray(arr, 0));
                            }
                        }
                        """);
                var list = new PlutusData.ListData(List.of(
                        new PlutusData.IntData(BigInteger.valueOf(10)),
                        new PlutusData.IntData(BigInteger.valueOf(20)),
                        new PlutusData.IntData(BigInteger.valueOf(30))));
                assertEquals(10, eval.call("test", list).asLong());
            }

            @Test
            void lastElement() {
                var eval = JulcEval.forSource(IMPORTS + """
                        class T {
                            static BigInteger test(PlutusData data) {
                                var list = Builtins.unListData(data);
                                var arr = Builtins.listToArray(list);
                                return Builtins.unIData(Builtins.indexArray(arr, 2));
                            }
                        }
                        """);
                var list = new PlutusData.ListData(List.of(
                        new PlutusData.IntData(BigInteger.valueOf(10)),
                        new PlutusData.IntData(BigInteger.valueOf(20)),
                        new PlutusData.IntData(BigInteger.valueOf(30))));
                assertEquals(30, eval.call("test", list).asLong());
            }

            @Test
            void middleElement() {
                var eval = JulcEval.forSource(IMPORTS + """
                        class T {
                            static BigInteger test(PlutusData data) {
                                var list = Builtins.unListData(data);
                                var arr = Builtins.listToArray(list);
                                return Builtins.unIData(Builtins.indexArray(arr, 1));
                            }
                        }
                        """);
                var list = new PlutusData.ListData(List.of(
                        new PlutusData.IntData(BigInteger.valueOf(10)),
                        new PlutusData.IntData(BigInteger.valueOf(20)),
                        new PlutusData.IntData(BigInteger.valueOf(30))));
                assertEquals(20, eval.call("test", list).asLong());
            }
        }

        @Nested
        class MultiIndex {

            @Test
            @org.junit.jupiter.api.Disabled("MultiIndexArray not yet supported by Scalus VM backend — works with julc-vm-java")
            void selectMultiple() {
                var eval = JulcEval.forSource(IMPORTS + """
                        class T {
                            static BigInteger test(PlutusData data) {
                                var list = Builtins.unListData(data);
                                var arr = Builtins.listToArray(list);
                                var indices = Builtins.mkCons(
                                    Builtins.iData(0),
                                    Builtins.mkCons(Builtins.iData(2), Builtins.mkNilData()));
                                var result = Builtins.multiIndexArray(arr, indices);
                                var first = Builtins.unIData(Builtins.headList(result));
                                var rest = Builtins.tailList(result);
                                var second = Builtins.unIData(Builtins.headList(rest));
                                return first.add(second);
                            }
                        }
                        """);
                var list = new PlutusData.ListData(List.of(
                        new PlutusData.IntData(BigInteger.valueOf(10)),
                        new PlutusData.IntData(BigInteger.valueOf(20)),
                        new PlutusData.IntData(BigInteger.valueOf(30))));
                // arr[0] + arr[2] = 10 + 30 = 40
                assertEquals(40, eval.call("test", list).asLong());
            }
        }

        @Nested
        class TypedArrayOps {

            @Test
            void toArrayFromListCompiles() {
                // Uses JulcList.toArray() → ListToArray builtin
                var eval = JulcEval.forSource("""
                        import com.bloxbean.cardano.julc.stdlib.Builtins;
                        import com.bloxbean.cardano.julc.core.PlutusData;
                        import com.bloxbean.cardano.julc.core.types.JulcList;
                        import com.bloxbean.cardano.julc.core.types.JulcArray;
                        import java.math.BigInteger;
                        class T {
                            static BigInteger test(JulcList<BigInteger> list) {
                                JulcArray<BigInteger> arr = list.toArray();
                                return arr.get(0);
                            }
                        }
                        """);
                var list = new PlutusData.ListData(List.of(
                        new PlutusData.IntData(BigInteger.valueOf(99))));
                assertEquals(99, eval.call("test", list).asLong());
            }

            @Test
            void fromListStaticFactory() {
                var eval = JulcEval.forSource("""
                        import com.bloxbean.cardano.julc.stdlib.Builtins;
                        import com.bloxbean.cardano.julc.core.PlutusData;
                        import com.bloxbean.cardano.julc.core.types.JulcList;
                        import com.bloxbean.cardano.julc.core.types.JulcArray;
                        import java.math.BigInteger;
                        class T {
                            static BigInteger test(JulcList<BigInteger> list) {
                                JulcArray<BigInteger> arr = JulcArray.fromList(list);
                                return BigInteger.valueOf(arr.length());
                            }
                        }
                        """);
                var list = new PlutusData.ListData(List.of(
                        new PlutusData.IntData(BigInteger.valueOf(1)),
                        new PlutusData.IntData(BigInteger.valueOf(2))));
                assertEquals(2, eval.call("test", list).asLong());
            }

            @Test
            void typedGetAtNonZeroIndex() {
                // Tests TypeMethodRegistry dispatch for arr.get(1) and arr.get(2) with typed array
                var eval = JulcEval.forSource("""
                        import com.bloxbean.cardano.julc.stdlib.Builtins;
                        import com.bloxbean.cardano.julc.core.PlutusData;
                        import com.bloxbean.cardano.julc.core.types.JulcList;
                        import com.bloxbean.cardano.julc.core.types.JulcArray;
                        import java.math.BigInteger;
                        class T {
                            static BigInteger test(JulcList<BigInteger> list) {
                                JulcArray<BigInteger> arr = list.toArray();
                                BigInteger second = arr.get(1);
                                BigInteger third = arr.get(2);
                                return second.add(third);
                            }
                        }
                        """);
                var list = new PlutusData.ListData(List.of(
                        new PlutusData.IntData(BigInteger.valueOf(10)),
                        new PlutusData.IntData(BigInteger.valueOf(20)),
                        new PlutusData.IntData(BigInteger.valueOf(30))));
                assertEquals(50, eval.call("test", list).asLong());
            }

            @Test
            void fromListThenGetPropagatesElementType() {
                // Tests element-type propagation through JulcArray.fromList() static factory + wrapDecode
                var eval = JulcEval.forSource("""
                        import com.bloxbean.cardano.julc.stdlib.Builtins;
                        import com.bloxbean.cardano.julc.core.PlutusData;
                        import com.bloxbean.cardano.julc.core.types.JulcList;
                        import com.bloxbean.cardano.julc.core.types.JulcArray;
                        import java.math.BigInteger;
                        class T {
                            static BigInteger test(JulcList<BigInteger> list) {
                                JulcArray<BigInteger> arr = JulcArray.fromList(list);
                                return arr.get(0);
                            }
                        }
                        """);
                var list = new PlutusData.ListData(List.of(
                        new PlutusData.IntData(BigInteger.valueOf(42))));
                assertEquals(42, eval.call("test", list).asLong());
            }

            @Test
            void arrayLengthTyped() {
                var eval = JulcEval.forSource("""
                        import com.bloxbean.cardano.julc.stdlib.Builtins;
                        import com.bloxbean.cardano.julc.core.PlutusData;
                        import com.bloxbean.cardano.julc.core.types.JulcList;
                        import com.bloxbean.cardano.julc.core.types.JulcArray;
                        import java.math.BigInteger;
                        class T {
                            static BigInteger test(JulcList<BigInteger> list) {
                                JulcArray<BigInteger> arr = list.toArray();
                                return BigInteger.valueOf(arr.length());
                            }
                        }
                        """);
                var list = new PlutusData.ListData(List.of(
                        new PlutusData.IntData(BigInteger.valueOf(1)),
                        new PlutusData.IntData(BigInteger.valueOf(2)),
                        new PlutusData.IntData(BigInteger.valueOf(3))));
                assertEquals(3, eval.call("test", list).asLong());
            }
        }
    }

    @Nested
    class OffChainTests {

        @Test
        void getReturnsCorrectElement() {
            JulcList<Integer> list = JulcList.of(10, 20, 30);
            JulcArray<Integer> arr = JulcArray.fromList(list);
            assertEquals(20, arr.get(1));
        }

        @Test
        void lengthReturnsCount() {
            JulcList<String> list = JulcList.of("a", "b", "c");
            JulcArray<String> arr = JulcArray.fromList(list);
            assertEquals(3, arr.length());
        }

        @Test
        void emptyArrayHasZeroLength() {
            JulcList<Integer> empty = JulcList.empty();
            JulcArray<Integer> arr = JulcArray.fromList(empty);
            assertEquals(0, arr.length());
        }

        @Test
        void firstAndLastElement() {
            JulcList<Integer> list = JulcList.of(100, 200, 300);
            JulcArray<Integer> arr = JulcArray.fromList(list);
            assertEquals(100, arr.get(0));
            assertEquals(300, arr.get(2));
        }

        @Test
        void toArrayFromJulcList() {
            JulcList<Integer> list = JulcList.of(5, 10, 15);
            JulcArray<Integer> arr = list.toArray();
            assertEquals(3, arr.length());
            assertEquals(10, arr.get(1));
        }

        @Test
        void equalsForSameElements() {
            JulcArray<Integer> a = JulcArray.fromList(JulcList.of(1, 2, 3));
            JulcArray<Integer> b = JulcArray.fromList(JulcList.of(1, 2, 3));
            assertEquals(a, b);
        }

        @Test
        void notEqualsForDifferentElements() {
            JulcArray<Integer> a = JulcArray.fromList(JulcList.of(1, 2, 3));
            JulcArray<Integer> b = JulcArray.fromList(JulcList.of(1, 2, 4));
            assertNotEquals(a, b);
        }

        @Test
        void hashCodeConsistentWithEquals() {
            JulcArray<Integer> a = JulcArray.fromList(JulcList.of(10, 20));
            JulcArray<Integer> b = JulcArray.fromList(JulcList.of(10, 20));
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void getThrowsOnOutOfBounds() {
            JulcArray<Integer> arr = JulcArray.fromList(JulcList.of(1, 2));
            assertThrows(IndexOutOfBoundsException.class, () -> arr.get(5));
        }
    }
}
