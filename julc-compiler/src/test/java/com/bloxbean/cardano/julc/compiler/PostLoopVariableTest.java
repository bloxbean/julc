package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for post-loop variable re-binding fix.
 * Verifies that variables defined before while/for-each loops remain accessible after the loop.
 * Covers: single-acc, multi-acc, unit-acc for both while and for-each; sequential loops; mixed loops.
 */
class PostLoopVariableTest {

    static JulcVm vm;
    static StdlibRegistry stdlib;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
        stdlib = StdlibRegistry.defaultRegistry();
    }

    static Program compileValidator(String source) {
        var compiler = new JulcCompiler();
        var result = compiler.compile(source);
        assertFalse(result.hasErrors(), "Compilation failed: " + result.diagnostics());
        assertNotNull(result.program(), "Program should not be null");
        return result.program();
    }

    static Program compileWithLedger(String source) {
        var compiler = new JulcCompiler(stdlib::lookup);
        var result = compiler.compile(source);
        assertFalse(result.hasErrors(), "Compilation failed: " + result.diagnostics());
        assertNotNull(result.program(), "Program should not be null");
        return result.program();
    }

    static PlutusData mockCtx(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),  // txInfo placeholder
                redeemer,
                PlutusData.integer(0)); // scriptInfo placeholder
    }

    static PlutusData alwaysInterval() {
        var negInf = PlutusData.constr(0);
        var posInf = PlutusData.constr(2);
        var trueVal = PlutusData.constr(1);
        var lower = PlutusData.constr(0, negInf, trueVal);
        var upper = PlutusData.constr(0, posInf, trueVal);
        return PlutusData.constr(0, lower, upper);
    }

    static PlutusData buildTxInfo(PlutusData... signatories) {
        return PlutusData.constr(0,
                PlutusData.list(),                          // 0: inputs
                PlutusData.list(),                          // 1: referenceInputs
                PlutusData.list(),                          // 2: outputs
                PlutusData.integer(2000000),                // 3: fee
                PlutusData.map(),                           // 4: mint
                PlutusData.list(),                          // 5: certificates
                PlutusData.map(),                           // 6: withdrawals
                alwaysInterval(),                           // 7: validRange
                PlutusData.list(signatories),               // 8: signatories
                PlutusData.map(),                           // 9: redeemers
                PlutusData.map(),                           // 10: datums
                PlutusData.bytes(new byte[32]),              // 11: txId
                PlutusData.map(),                           // 12: votes
                PlutusData.list(),                          // 13: proposalProcedures
                PlutusData.constr(1),                       // 14: currentTreasuryAmount (None)
                PlutusData.constr(1)                        // 15: treasuryDonation (None)
        );
    }

    static PlutusData buildScriptContext(PlutusData txInfo, PlutusData redeemer) {
        return PlutusData.constr(0, txInfo, redeemer, PlutusData.integer(0));
    }

    // ===== While loop pre-loop variable tests (existing S1 fix) =====

    @Test
    void testPreLoopVarAccessibleAfterMultiAccWhile() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long x = 5;
                    long acc1 = 0;
                    long acc2 = 0;
                    long counter = 3;
                    while (counter > 0) {
                        acc1 = acc1 + 1;
                        acc2 = acc2 + 2;
                        counter = counter - 1;
                    }
                    return (x + acc1) == 8;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Pre-loop var x should be accessible after multi-acc while. Got: " + result);
    }

    @Test
    void testPreLoopVarAccessibleAfterSingleAccWhile() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long x = 5;
                    long acc = 0;
                    long counter = 3;
                    while (counter > 0) {
                        acc = acc + counter;
                        counter = counter - 1;
                    }
                    return (x + acc) == 11;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Pre-loop var x should be accessible after single-acc while. Got: " + result);
    }

    @Test
    void testMultiplePreLoopVarsPreserved() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long x = 5;
                    long y = 10;
                    long acc1 = 0;
                    long acc2 = 0;
                    long counter = 2;
                    while (counter > 0) {
                        acc1 = acc1 + 1;
                        acc2 = acc2 + 1;
                        counter = counter - 1;
                    }
                    return (x + y + acc1 + acc2) == 19;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Multiple pre-loop vars should be preserved. Got: " + result);
    }

    @Test
    void testPreLoopVarInReturnAfterWhile() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long x = 5;
                    long acc1 = 0;
                    long acc2 = 0;
                    long counter = 3;
                    while (counter > 0) {
                        acc1 = acc1 + 1;
                        acc2 = acc2 + 1;
                        counter = counter - 1;
                    }
                    return x == 5;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Pre-loop var x should still be 5 after while. Got: " + result);
    }

    @Test
    void testAccumulatorsStillWork() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long acc1 = 0;
                    long acc2 = 100;
                    long counter = 3;
                    while (counter > 0) {
                        acc1 = acc1 + 10;
                        acc2 = acc2 - 10;
                        counter = counter - 1;
                    }
                    return (acc1 + acc2) == 100;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Accumulators should have correct values. Got: " + result);
    }

    @Test
    void testNestedWhilePreservesOuterVars() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long x = 42;
                    long acc = 0;
                    long outerCounter = 2;
                    while (outerCounter > 0) {
                        acc = acc + 1;
                        outerCounter = outerCounter - 1;
                    }
                    return x == 42;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Outer var should be preserved after while. Got: " + result);
    }

    @Test
    void testForEachUnaffected() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long x = 7;
                    long sum = 0;
                    long counter = 3;
                    while (counter > 0) {
                        sum = sum + 1;
                        counter = counter - 1;
                    }
                    return x == 7;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Pre-loop var with simple loop should work. Got: " + result);
    }

    @Test
    void testNoPreLoopVarsNoChange() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long acc1 = 0;
                    long acc2 = 0;
                    long counter = 5;
                    while (counter > 0) {
                        acc1 = acc1 + 1;
                        acc2 = acc2 + 2;
                        counter = counter - 1;
                    }
                    return acc1 == 5;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "No pre-loop vars case should work. Got: " + result);
    }

    @Test
    void testPreLoopVarUsedInConditionAndAfter() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long limit = 3;
                    long acc1 = 0;
                    long acc2 = 0;
                    long counter = 5;
                    while (counter > 0) {
                        acc1 = acc1 + 1;
                        acc2 = acc2 + 1;
                        counter = counter - 1;
                    }
                    return acc1 > limit;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Pre-loop var in condition and post-loop should work. Got: " + result);
    }

    @Test
    void testWhileAsLastStatementNoRebinding() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long acc1 = 0;
                    long acc2 = 0;
                    long counter = 3;
                    while (counter > 0) {
                        acc1 = acc1 + 1;
                        acc2 = acc2 + 1;
                        counter = counter - 1;
                    }
                    return acc1 == 3;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "While as last statement should work. Got: " + result);
    }

    // ===== For-each loop pre-loop variable tests (Path A, B, C fixes) =====

    @Test
    void testForEachSingleAccPreLoopVar() {
        // Pre-loop var x=5, for-each single-acc over signatories, then use x + count
        var source = """
            import java.math.BigInteger;
            import com.bloxbean.cardano.julc.onchain.ledger.*;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(byte[] redeemer, ScriptContext ctx) {
                    long x = 5;
                    var txInfo = ctx.txInfo();
                    long count = 0;
                    for (var sig : txInfo.signatories()) {
                        count = count + 1;
                    }
                    // 3 signatories → count=3, x=5 → x + count = 8
                    return (x + count) == 8;
                }
            }
            """;
        var program = compileWithLedger(source);
        var txInfo = buildTxInfo(
                PlutusData.bytes(new byte[]{1}),
                PlutusData.bytes(new byte[]{2}),
                PlutusData.bytes(new byte[]{3}));
        var ctx = buildScriptContext(txInfo, PlutusData.bytes(new byte[]{1}));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "For-each single-acc: pre-loop var x should be accessible. Got: " + result);
    }

    @Test
    void testForEachMultiAccPreLoopVar() {
        // Pre-loop var x=5, for-each multi-acc over signatories, then use x + count + doubled
        var source = """
            import java.math.BigInteger;
            import com.bloxbean.cardano.julc.onchain.ledger.*;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(byte[] redeemer, ScriptContext ctx) {
                    long x = 5;
                    var txInfo = ctx.txInfo();
                    long count = 0;
                    long doubled = 0;
                    for (var sig : txInfo.signatories()) {
                        count = count + 1;
                        doubled = doubled + 2;
                    }
                    // 3 signatories → count=3, doubled=6, x=5 → x + count + doubled = 14
                    return (x + count + doubled) == 14;
                }
            }
            """;
        var program = compileWithLedger(source);
        var txInfo = buildTxInfo(
                PlutusData.bytes(new byte[]{1}),
                PlutusData.bytes(new byte[]{2}),
                PlutusData.bytes(new byte[]{3}));
        var ctx = buildScriptContext(txInfo, PlutusData.bytes(new byte[]{1}));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "For-each multi-acc: pre-loop var x should be accessible. Got: " + result);
    }

    @Test
    void testForEachUnitAccPreLoopVar() {
        // Pre-loop var x=5, for-each with no accumulator (unit-acc), then use x
        var source = """
            import java.math.BigInteger;
            import com.bloxbean.cardano.julc.onchain.ledger.*;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(byte[] redeemer, ScriptContext ctx) {
                    long x = 5;
                    var txInfo = ctx.txInfo();
                    for (var sig : txInfo.signatories()) {
                        long unused = 1;
                    }
                    return x == 5;
                }
            }
            """;
        var program = compileWithLedger(source);
        var txInfo = buildTxInfo(PlutusData.bytes(new byte[]{1}), PlutusData.bytes(new byte[]{2}));
        var ctx = buildScriptContext(txInfo, PlutusData.bytes(new byte[]{1}));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "For-each unit-acc: pre-loop var x should be accessible. Got: " + result);
    }

    // ===== Sequential and mixed loop tests =====

    @Test
    void testSequentialWhileLoops() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long x = 5;
                    long acc1 = 0;
                    long counter1 = 3;
                    while (counter1 > 0) {
                        acc1 = acc1 + 1;
                        counter1 = counter1 - 1;
                    }
                    long acc2 = 0;
                    long counter2 = 2;
                    while (counter2 > 0) {
                        acc2 = acc2 + 10;
                        counter2 = counter2 - 1;
                    }
                    return (x + acc1 + acc2) == 28;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Sequential while loops: pre-loop var x should survive both. Got: " + result);
    }

    @Test
    void testThreeSequentialLoops() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long x = 1;
                    long a = 0;
                    long c1 = 2;
                    while (c1 > 0) {
                        a = a + 1;
                        c1 = c1 - 1;
                    }
                    long b = 0;
                    long c2 = 3;
                    while (c2 > 0) {
                        b = b + 1;
                        c2 = c2 - 1;
                    }
                    long c = 0;
                    long c3 = 4;
                    while (c3 > 0) {
                        c = c + 1;
                        c3 = c3 - 1;
                    }
                    return (x + a + b + c) == 10;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Three sequential loops: pre-loop var x should survive all. Got: " + result);
    }

    @Test
    void testWhileThenForEachPreLoopVar() {
        // Pre-loop var x=5, while loop then for-each over signatories, use x + both accumulators
        var source = """
            import java.math.BigInteger;
            import com.bloxbean.cardano.julc.onchain.ledger.*;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(byte[] redeemer, ScriptContext ctx) {
                    long x = 5;
                    long acc1 = 0;
                    long counter = 3;
                    while (counter > 0) {
                        acc1 = acc1 + 1;
                        counter = counter - 1;
                    }
                    var txInfo = ctx.txInfo();
                    long acc2 = 0;
                    for (var sig : txInfo.signatories()) {
                        acc2 = acc2 + 1;
                    }
                    // acc1=3, acc2=3 (3 signatories), x=5 → x + acc1 + acc2 = 11
                    return (x + acc1 + acc2) == 11;
                }
            }
            """;
        var program = compileWithLedger(source);
        var txInfo = buildTxInfo(
                PlutusData.bytes(new byte[]{1}),
                PlutusData.bytes(new byte[]{2}),
                PlutusData.bytes(new byte[]{3}));
        var ctx = buildScriptContext(txInfo, PlutusData.bytes(new byte[]{1}));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "While then for-each: pre-loop var x should survive both. Got: " + result);
    }

    @Test
    void testForEachThenWhilePreLoopVar() {
        // Pre-loop var x=5, for-each over signatories then while loop, use x + both accumulators
        var source = """
            import java.math.BigInteger;
            import com.bloxbean.cardano.julc.onchain.ledger.*;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(byte[] redeemer, ScriptContext ctx) {
                    long x = 5;
                    var txInfo = ctx.txInfo();
                    long acc1 = 0;
                    for (var sig : txInfo.signatories()) {
                        acc1 = acc1 + 1;
                    }
                    long acc2 = 0;
                    long counter = 2;
                    while (counter > 0) {
                        acc2 = acc2 + 10;
                        counter = counter - 1;
                    }
                    // acc1=3 (3 signatories), acc2=20, x=5 → x + acc1 + acc2 = 28
                    return (x + acc1 + acc2) == 28;
                }
            }
            """;
        var program = compileWithLedger(source);
        var txInfo = buildTxInfo(
                PlutusData.bytes(new byte[]{1}),
                PlutusData.bytes(new byte[]{2}),
                PlutusData.bytes(new byte[]{3}));
        var ctx = buildScriptContext(txInfo, PlutusData.bytes(new byte[]{1}));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "For-each then while: pre-loop var x should survive both. Got: " + result);
    }

    @Test
    void testHelperMethodCallAfterLoop() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                static long add(long a, long b) {
                    return a + b;
                }

                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long x = 5;
                    long acc = 0;
                    long counter = 3;
                    while (counter > 0) {
                        acc = acc + 1;
                        counter = counter - 1;
                    }
                    return add(x, acc) == 8;
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Helper method should receive pre-loop var correctly. Got: " + result);
    }

    @Test
    void testMultiplePreLoopVarsForEach() {
        // Three pre-loop vars a=1, b=2, c=3, for-each single-acc over signatories, use all after
        var source = """
            import java.math.BigInteger;
            import com.bloxbean.cardano.julc.onchain.ledger.*;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(byte[] redeemer, ScriptContext ctx) {
                    long a = 1;
                    long b = 2;
                    long c = 3;
                    var txInfo = ctx.txInfo();
                    long count = 0;
                    for (var sig : txInfo.signatories()) {
                        count = count + 1;
                    }
                    // 3 signatories → count=3, a+b+c+count = 1+2+3+3 = 9
                    return (a + b + c + count) == 9;
                }
            }
            """;
        var program = compileWithLedger(source);
        var txInfo = buildTxInfo(
                PlutusData.bytes(new byte[]{1}),
                PlutusData.bytes(new byte[]{2}),
                PlutusData.bytes(new byte[]{3}));
        var ctx = buildScriptContext(txInfo, PlutusData.bytes(new byte[]{1}));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "Multiple pre-loop vars should all be accessible after for-each. Got: " + result);
    }

    @Test
    void testPreLoopVarInIfAfterLoop() {
        var source = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    long x = 5;
                    long acc1 = 0;
                    long acc2 = 0;
                    long counter = 3;
                    while (counter > 0) {
                        acc1 = acc1 + 1;
                        acc2 = acc2 + 2;
                        counter = counter - 1;
                    }
                    if (acc1 > 0) {
                        return x == 5;
                    } else {
                        return acc2 == 0;
                    }
                }
            }
            """;
        var program = compileValidator(source);
        var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(result.isSuccess(), "Pre-loop var in conditional after loop should work. Got: " + result);
    }
}
