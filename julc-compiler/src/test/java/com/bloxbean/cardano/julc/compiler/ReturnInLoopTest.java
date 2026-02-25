package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Fix 1: Reject 'return' inside while/for-each loop body.
 * Tests for Fix 2: Pair list accumulator detection from initial value.
 */
class ReturnInLoopTest {

    static JulcVm vm;
    static StdlibRegistry stdlib;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
        stdlib = StdlibRegistry.defaultRegistry();
    }

    static Program compileValidator(String source) {
        var compiler = new JulcCompiler(stdlib::lookup);
        var result = compiler.compile(source);
        assertFalse(result.hasErrors(), "Compilation failed: " + result.diagnostics());
        assertNotNull(result.program(), "Program should not be null");
        return result.program();
    }

    static PlutusData mockCtx(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),
                redeemer,
                PlutusData.integer(0));
    }

    // ===== Fix 1: Return-in-while error detection =====

    @Nested
    class WhileLoopReturnTests {

        @Test
        void returnInWhileBodyRejected() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        long counter = 3;
                        while (counter > 0) {
                            return true;
                        }
                        return false;
                    }
                }
                """;
            var ex = assertThrows(CompilerException.class, () -> new JulcCompiler(stdlib::lookup).compile(source));
            assertTrue(ex.getMessage().contains("return"),
                    "Should mention 'return'. Got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("while"),
                    "Should mention 'while'. Got: " + ex.getMessage());
        }

        @Test
        void returnInIfInsideWhileRejected() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        long counter = 3;
                        long acc = 0;
                        while (counter > 0) {
                            if (acc > 5) {
                                return true;
                            }
                            acc = acc + counter;
                            counter = counter - 1;
                        }
                        return false;
                    }
                }
                """;
            var ex = assertThrows(CompilerException.class, () -> new JulcCompiler(stdlib::lookup).compile(source));
            assertTrue(ex.getMessage().contains("return"),
                    "Should mention 'return'. Got: " + ex.getMessage());
        }

        @Test
        void returnInElseInsideWhileRejected() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        long counter = 3;
                        long acc = 0;
                        while (counter > 0) {
                            if (acc > 5) {
                                acc = acc + 1;
                            } else {
                                return false;
                            }
                            counter = counter - 1;
                        }
                        return true;
                    }
                }
                """;
            var ex = assertThrows(CompilerException.class, () -> new JulcCompiler(stdlib::lookup).compile(source));
            assertTrue(ex.getMessage().contains("return"),
                    "Should mention 'return'. Got: " + ex.getMessage());
        }

        @Test
        void returnInNestedInnerWhileOnlyTriggersInner() {
            // return in inner while — only inner while triggers error, outer is fine
            var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        long outer = 2;
                        long acc = 0;
                        while (outer > 0) {
                            long inner = 2;
                            while (inner > 0) {
                                return true;
                            }
                            acc = acc + 1;
                            outer = outer - 1;
                        }
                        return acc == 2;
                    }
                }
                """;
            // The inner while has return, so it should be caught
            // containsReturn stops at nested loops, so the INNER generateWhileStmt catches it
            var ex = assertThrows(CompilerException.class, () -> new JulcCompiler(stdlib::lookup).compile(source));
            assertTrue(ex.getMessage().contains("return"),
                    "Should catch return in inner while. Got: " + ex.getMessage());
        }

        @Test
        void breakPlusReturnAfterLoopCompilesFine() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        long counter = 5;
                        long acc = 0;
                        while (counter > 0) {
                            if (acc > 3) {
                                break;
                            }
                            acc = acc + counter;
                            counter = counter - 1;
                        }
                        return acc > 3;
                    }
                }
                """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "break + return after loop should work. Got: " + result);
        }

        @Test
        void accumulatorPatternCompilesFine() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        long counter = 3;
                        long sum = 0;
                        while (counter > 0) {
                            sum = sum + counter;
                            counter = counter - 1;
                        }
                        return sum == 6;
                    }
                }
                """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Accumulator pattern should compile and eval. Got: " + result);
        }
    }

    // ===== Fix 1: Return-in-foreach error detection =====

    @Nested
    class ForEachReturnTests {

        @Test
        void returnInForEachBodyRejected() {
            var source = """
                import java.math.BigInteger;
                import java.util.List;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        List<PlutusData> items = (List<PlutusData>) redeemer;
                        for (var item : items) {
                            return true;
                        }
                        return false;
                    }
                }
                """;
            var ex = assertThrows(CompilerException.class, () -> new JulcCompiler(stdlib::lookup).compile(source));
            assertTrue(ex.getMessage().contains("return"),
                    "Should mention 'return'. Got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("for-each"),
                    "Should mention 'for-each'. Got: " + ex.getMessage());
        }

        @Test
        void returnInIfInsideForEachRejected() {
            var source = """
                import java.math.BigInteger;
                import java.util.List;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        List<PlutusData> items = (List<PlutusData>) redeemer;
                        boolean found = false;
                        for (var item : items) {
                            if (item == redeemer) {
                                return true;
                            }
                            found = true;
                        }
                        return found;
                    }
                }
                """;
            var ex = assertThrows(CompilerException.class, () -> new JulcCompiler(stdlib::lookup).compile(source));
            assertTrue(ex.getMessage().contains("return"),
                    "Should mention 'return'. Got: " + ex.getMessage());
        }

        @Test
        void forEachWithBreakCompilesFine() {
            var source = """
                import java.math.BigInteger;
                import java.util.List;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        List<PlutusData> items = (List<PlutusData>) redeemer;
                        boolean found = false;
                        for (var item : items) {
                            if (item == redeemer) {
                                found = true;
                                break;
                            }
                        }
                        return found;
                    }
                }
                """;
            // Should compile without error — break is fine
            var program = compileValidator(source);
            assertNotNull(program);
        }

        @Test
        void forEachAccumulatorPatternCompilesFine() {
            var source = """
                import java.math.BigInteger;
                import java.util.List;
                import com.bloxbean.cardano.julc.stdlib.Builtins;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        PlutusData items = Builtins.unListData(redeemer);
                        long sum = 0;
                        for (var item : items) {
                            sum = sum + Builtins.unIData(item);
                        }
                        return sum > 0;
                    }
                }
                """;
            var program = compileValidator(source);
            // Evaluate with a list [1, 2, 3]
            var list = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(list)));
            assertTrue(result.isSuccess(), "for-each accumulator pattern should work. Got: " + result);
        }
    }

    // ===== Fix 2: Pair list accumulator init detection =====

    @Nested
    class PairListAccumulatorInitTests {

        @Test
        void mkNilPairDataInitDetectedAsMapType() {
            // Multi-acc: one acc initialized from mkNilPairData(), one is a counter
            // The pair list acc should be typed as MapType for correct MkCons packing
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.stdlib.Builtins;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        PlutusData result = Builtins.mkNilPairData();
                        long counter = 3;
                        while (counter > 0) {
                            PlutusData pair = Builtins.mkPairData(Builtins.iData(counter), Builtins.iData(counter));
                            result = Builtins.mkCons(pair, result);
                            counter = counter - 1;
                        }
                        return Builtins.nullList(Builtins.unMapData(Builtins.mapData(result))) == false;
                    }
                }
                """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "mkNilPairData init should be typed as MapType. Got: " + result);
        }

        @Test
        void mkNilDataInitStaysListType() {
            // mkNilData() init should NOT be promoted to MapType
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.stdlib.Builtins;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        PlutusData result = Builtins.mkNilData();
                        long counter = 3;
                        while (counter > 0) {
                            result = Builtins.mkCons(Builtins.iData(counter), result);
                            counter = counter - 1;
                        }
                        return Builtins.nullList(Builtins.unListData(Builtins.listData(result))) == false;
                    }
                }
                """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "mkNilData init should stay ListType. Got: " + result);
        }

        @Test
        void multiAccMixedPairListAndCounter() {
            // Multi-acc: pair list from mkNilPairData + BigInteger counter
            // Both should be typed correctly
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.stdlib.Builtins;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        PlutusData pairs = Builtins.mkNilPairData();
                        long count = 0;
                        long items = 3;
                        while (items > 0) {
                            PlutusData pair = Builtins.mkPairData(Builtins.iData(items), Builtins.iData(items * 10));
                            pairs = Builtins.mkCons(pair, pairs);
                            count = count + 1;
                            items = items - 1;
                        }
                        return count == 3;
                    }
                }
                """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Multi-acc with pair list + counter should work. Got: " + result);
        }
    }
}
