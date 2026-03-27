package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PV11 Batch 6 builtins exposed through {@link com.bloxbean.cardano.julc.stdlib.Builtins}.
 */
class Pv11BuiltinsTest {

    private static final String IMPORTS = """
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            import com.bloxbean.cardano.julc.core.PlutusData;
            import java.math.BigInteger;
            """;

    @Nested
    class DropList {

        @Test
        void dropZero() {
            // DropList operates on UPLC lists, so we pass Data and unListData inside
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static BigInteger test(PlutusData data) {
                            var list = Builtins.unListData(data);
                            var result = Builtins.dropList(0, list);
                            return Builtins.unIData(Builtins.headList(result));
                        }
                    }
                    """);
            var list = new com.bloxbean.cardano.julc.core.PlutusData.ListData(java.util.List.of(
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(1)),
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(2)),
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(3))));
            assertEquals(1, eval.call("test", list).asLong());
        }

        @Test
        void dropSome() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static BigInteger test(PlutusData data) {
                            var list = Builtins.unListData(data);
                            var result = Builtins.dropList(2, list);
                            return Builtins.unIData(Builtins.headList(result));
                        }
                    }
                    """);
            var list = new com.bloxbean.cardano.julc.core.PlutusData.ListData(java.util.List.of(
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(1)),
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(2)),
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(3)),
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(4))));
            assertEquals(3, eval.call("test", list).asLong());
        }

        @Test
        void dropAll() {
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(PlutusData data) {
                            var list = Builtins.unListData(data);
                            var result = Builtins.dropList(100, list);
                            return Builtins.nullList(result);
                        }
                    }
                    """);
            var list = new com.bloxbean.cardano.julc.core.PlutusData.ListData(java.util.List.of(
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(1)),
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(2)),
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(3))));
            assertTrue(eval.call("test", list).asBoolean());
        }

        @Test
        void dropExactListSize() {
            // Boundary: drop exactly list.size() elements → empty list
            var eval = JulcEval.forSource(IMPORTS + """
                    class T {
                        static boolean test(PlutusData data) {
                            var list = Builtins.unListData(data);
                            var result = Builtins.dropList(3, list);
                            return Builtins.nullList(result);
                        }
                    }
                    """);
            var list = new com.bloxbean.cardano.julc.core.PlutusData.ListData(java.util.List.of(
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(1)),
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(2)),
                    new com.bloxbean.cardano.julc.core.PlutusData.IntData(java.math.BigInteger.valueOf(3))));
            assertTrue(eval.call("test", list).asBoolean());
        }
    }
}
