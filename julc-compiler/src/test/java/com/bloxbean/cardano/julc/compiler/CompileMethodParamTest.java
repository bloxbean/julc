package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.TermExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for @Param support in compileMethod().
 */
class CompileMethodParamTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    private CompileResult compile(String source, String methodName) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        return compiler.compileMethod(source, methodName);
    }

    private BigInteger evalInteger(CompileResult result, PlutusData... args) {
        var evalResult = vm.evaluateWithArgs(result.program(), List.of(args));
        var term = TermExtractor.extractResultTerm(evalResult);
        return TermExtractor.extractInteger(term);
    }

    private boolean evalBoolean(CompileResult result, PlutusData... args) {
        var evalResult = vm.evaluateWithArgs(result.program(), List.of(args));
        var term = TermExtractor.extractResultTerm(evalResult);
        return TermExtractor.extractBoolean(term);
    }

    // --- ParamInfo detection ---

    @Test
    void singleParamDetected() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                import com.bloxbean.cardano.julc.core.PlutusData;
                class ParamClass {
                    @Param PlutusData threshold;
                    static BigInteger getThreshold() {
                        return (BigInteger)(Object) threshold;
                    }
                }
                """;
        var result = compile(source, "getThreshold");
        assertFalse(result.hasErrors());
        assertTrue(result.isParameterized());
        assertEquals(1, result.params().size());
        assertEquals("threshold", result.params().get(0).name());
        assertEquals("PlutusData", result.params().get(0).type());
    }

    @Test
    void multipleParamsDetected() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                import com.bloxbean.cardano.julc.core.PlutusData;
                class MultiParam {
                    @Param PlutusData owner;
                    @Param PlutusData threshold;
                    static BigInteger getThreshold() {
                        return (BigInteger)(Object) threshold;
                    }
                }
                """;
        var result = compile(source, "getThreshold");
        assertFalse(result.hasErrors());
        assertEquals(2, result.params().size());
        assertEquals("owner", result.params().get(0).name());
        assertEquals("threshold", result.params().get(1).name());
    }

    @Test
    void nonParameterizedClassReturnsEmptyParams() {
        var source = """
                class NoParam {
                    static BigInteger doubleIt(BigInteger x) { return x * 2; }
                }
                """;
        var result = compile(source, "doubleIt");
        assertFalse(result.hasErrors());
        assertFalse(result.isParameterized());
        assertTrue(result.params().isEmpty());
    }

    // --- Evaluation with params ---

    @Test
    void singleParamReturnedDirectly() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                class ParamReturn {
                    @Param BigInteger threshold;
                    static BigInteger getThreshold() {
                        return threshold;
                    }
                }
                """;
        var result = compile(source, "getThreshold");
        assertFalse(result.hasErrors());
        // Apply param=42, then no method args
        var value = evalInteger(result, PlutusData.integer(42));
        assertEquals(BigInteger.valueOf(42), value);
    }

    @Test
    void paramUsedInComputation() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                class ParamCompute {
                    @Param BigInteger multiplier;
                    static BigInteger multiply(BigInteger x) {
                        return multiplier * x;
                    }
                }
                """;
        var result = compile(source, "multiply");
        assertFalse(result.hasErrors());
        // Apply param=3, then method arg=7
        var value = evalInteger(result, PlutusData.integer(3), PlutusData.integer(7));
        assertEquals(BigInteger.valueOf(21), value);
    }

    @Test
    void multipleParamsCorrectOrder() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                class MultiParamOrder {
                    @Param BigInteger a;
                    @Param BigInteger b;
                    static BigInteger subtract() {
                        return a - b;
                    }
                }
                """;
        var result = compile(source, "subtract");
        assertFalse(result.hasErrors());
        // Apply a=10, b=3 → 10-3 = 7
        var value = evalInteger(result, PlutusData.integer(10), PlutusData.integer(3));
        assertEquals(BigInteger.valueOf(7), value);
    }

    @Test
    void paramPlusMethodArgs() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                class ParamPlusArgs {
                    @Param BigInteger base;
                    static BigInteger addToBase(BigInteger x) {
                        return base + x;
                    }
                }
                """;
        var result = compile(source, "addToBase");
        assertFalse(result.hasErrors());
        // Apply param=100, method arg=42
        var value = evalInteger(result, PlutusData.integer(100), PlutusData.integer(42));
        assertEquals(BigInteger.valueOf(142), value);
    }

    @Test
    void paramWithBooleanResult() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                class ParamBool {
                    @Param BigInteger threshold;
                    static boolean isAboveThreshold(BigInteger x) {
                        return x > threshold;
                    }
                }
                """;
        var result = compile(source, "isAboveThreshold");
        assertFalse(result.hasErrors());
        // threshold=10, x=15 → true
        assertTrue(evalBoolean(result, PlutusData.integer(10), PlutusData.integer(15)));
        // threshold=10, x=5 → false
        assertFalse(evalBoolean(result, PlutusData.integer(10), PlutusData.integer(5)));
    }

    @Test
    void methodNotReferencingParamStillCompiles() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                class ParamUnused {
                    @Param BigInteger threshold;
                    static BigInteger doubleIt(BigInteger x) {
                        return x * 2;
                    }
                }
                """;
        var result = compile(source, "doubleIt");
        assertFalse(result.hasErrors());
        // Even though doubleIt doesn't use threshold, the param lambda is still there
        assertTrue(result.isParameterized());
        // Apply dummy param=0, method arg=21
        var value = evalInteger(result, PlutusData.integer(0), PlutusData.integer(21));
        assertEquals(BigInteger.valueOf(42), value);
    }

    // --- Banned @Param types ---

    @Test
    void bannedParamTypeProducesError() {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.Param;
                import com.bloxbean.cardano.julc.core.PlutusData;
                class BadParam {
                    @Param PlutusData.BytesData owner;
                    static BigInteger dummy() { return 1; }
                }
                """;
        assertThrows(CompilerException.class, () -> compile(source, "dummy"));
    }
}
