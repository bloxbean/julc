package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PlutusData.cast(data, TargetType.class) — the clean replacement
 * for the (TargetType)(Object) data double-cast pattern.
 */
class PlutusDataCastTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    static PlutusData buildScriptContext(PlutusData txInfo, PlutusData redeemer, PlutusData scriptInfo) {
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    static PlutusData buildTxInfo(PlutusData[] signatories, PlutusData validRange) {
        return PlutusData.constr(0,
                PlutusData.list(),                                     // 0: inputs
                PlutusData.list(),                                     // 1: referenceInputs
                PlutusData.list(),                                     // 2: outputs
                PlutusData.integer(2000000),                           // 3: fee
                PlutusData.map(),                                      // 4: mint
                PlutusData.list(),                                     // 5: certificates
                PlutusData.map(),                                      // 6: withdrawals
                validRange,                                            // 7: validRange
                PlutusData.list(signatories),                          // 8: signatories
                PlutusData.map(),                                      // 9: redeemers
                PlutusData.map(),                                      // 10: datums
                PlutusData.bytes(new byte[32]),                        // 11: txId
                PlutusData.map(),                                      // 12: votes
                PlutusData.list(),                                     // 13: proposalProcedures
                PlutusData.constr(1),                                  // 14: currentTreasuryAmount (None)
                PlutusData.constr(1)                                   // 15: treasuryDonation (None)
        );
    }

    static PlutusData alwaysInterval() {
        var negInf = PlutusData.constr(0);
        var posInf = PlutusData.constr(2);
        var trueVal = PlutusData.constr(1);
        var lowerBound = PlutusData.constr(0, negInf, trueVal);
        var upperBound = PlutusData.constr(0, posInf, trueVal);
        return PlutusData.constr(0, lowerBound, upperBound);
    }

    static PlutusData simpleCtx() {
        return buildScriptContext(
                buildTxInfo(new PlutusData[0], alwaysInterval()),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
    }

    /**
     * Test 1: Cast to custom record → verify field access works.
     */
    @Test
    void castToCustomRecord_fieldAccess() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    record MyDatum(BigInteger amount, byte[] owner) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        var datum = PlutusData.cast(redeemer, MyDatum.class);
                        return datum.amount() == 42;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();
        var redeemer = PlutusData.constr(0, PlutusData.integer(42), PlutusData.bytes(new byte[28]));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0], alwaysInterval()),
                redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "PlutusData.cast to record should work. Got: " + result);
    }

    /**
     * Test 2: Cast to JulcMap → verify MapType (UnMapData emitted).
     */
    @Test
    void castToJulcMap_mapOps() {
        var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.core.types.JulcMap;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        var m = PlutusData.cast(redeemer, JulcMap.class);
                        return m.size() == 2;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();
        var redeemer = PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0], alwaysInterval()),
                redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "PlutusData.cast to JulcMap should emit UnMapData. Got: " + result);
    }

    /**
     * Test 3: Cast to JulcList → verify list operations work.
     * Uses a record field that is already a list, since redeemer is raw Data.
     */
    @Test
    void castToJulcList_listOps() {
        var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.core.types.JulcList;

                @Validator
                class TestValidator {
                    record Wrapper(JulcList<PlutusData> items) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        var w = PlutusData.cast(redeemer, Wrapper.class);
                        var items = w.items();
                        return items.size() == 3;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();
        // Wrapper(items=[1,2,3]) → ConstrData(0, [ListData([IData(1), IData(2), IData(3)])])
        var redeemer = PlutusData.constr(0,
                PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0], alwaysInterval()),
                redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "PlutusData.cast to record with JulcList field should work. Got: " + result);
    }

    /**
     * Test 4: Cast with var → verify type inference from ClassExpr.
     */
    @Test
    void castWithVar_typeInferred() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    record Point(BigInteger x, BigInteger y) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        var p = PlutusData.cast(redeemer, Point.class);
                        return p.x() == 10 && p.y() == 20;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();
        var redeemer = PlutusData.constr(0, PlutusData.integer(10), PlutusData.integer(20));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0], alwaysInterval()),
                redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "var + PlutusData.cast should infer type. Got: " + result);
    }

    /**
     * Test 5: Cast to sealed interface (SumType) → verify switch works.
     */
    @Test
    void castToSealedInterface_switchWorks() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    sealed interface Action permits Mint, Burn {}
                    record Mint(BigInteger amount) implements Action {}
                    record Burn(BigInteger amount) implements Action {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        var action = PlutusData.cast(redeemer, Action.class);
                        return switch (action) {
                            case Mint m -> m.amount() == 100;
                            case Burn b -> b.amount() == 0;
                        };
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();
        // Mint(100) → ConstrData(0, [IData(100)])
        var redeemer = PlutusData.constr(0, PlutusData.integer(100));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0], alwaysInterval()),
                redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "PlutusData.cast to sealed interface + switch should work. Got: " + result);
    }

    /**
     * Test 6: Cast to ledger type (Value) → verify field access.
     */
    @Test
    void castToLedgerType_fieldAccess() {
        var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.api.v3.Value;

                @Validator
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        var val = PlutusData.cast(redeemer, Value.class);
                        return val.isEmpty();
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();
        // Empty Value = MapData([])
        var redeemer = PlutusData.map();
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0], alwaysInterval()),
                redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "PlutusData.cast to Value should work. Got: " + result);
    }

    /**
     * Test 7: Regression — old double-cast pattern still works.
     */
    @Test
    void doubleCastPattern_stillWorks() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    record MyDatum(BigInteger amount, byte[] owner) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        MyDatum datum = (MyDatum)(Object) redeemer;
                        return datum.amount() == 42;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();
        var redeemer = PlutusData.constr(0, PlutusData.integer(42), PlutusData.bytes(new byte[28]));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0], alwaysInterval()),
                redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "Old double-cast pattern should still work. Got: " + result);
    }

    /**
     * Test 8: Verify that PlutusData.cast() produces identity PIR (same as double-cast).
     */
    @Test
    void castProducesIdentityPir() {
        var castSource = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    record MyDatum(BigInteger amount) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        var datum = PlutusData.cast(redeemer, MyDatum.class);
                        return datum.amount() == 42;
                    }
                }
                """;
        var doubleCastSource = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    record MyDatum(BigInteger amount) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        MyDatum datum = (MyDatum)(Object) redeemer;
                        return datum.amount() == 42;
                    }
                }
                """;
        var castResult = new JulcCompiler().compileWithDetails(castSource);
        var doubleCastResult = new JulcCompiler().compileWithDetails(doubleCastSource);
        assertNotNull(castResult.program());
        assertNotNull(doubleCastResult.program());
        assertFalse(castResult.hasErrors());
        assertFalse(doubleCastResult.hasErrors());
        // Both should produce the same UPLC
        assertEquals(doubleCastResult.program().term().toString(),
                castResult.program().term().toString(),
                "PlutusData.cast and double-cast should produce identical UPLC");
    }

    /**
     * Test 9: JVM-level PlutusData.cast() works correctly (off-chain).
     */
    @Test
    void jvmCast_offChainWorks() {
        PlutusData data = PlutusData.constr(0, PlutusData.integer(42));
        // Simulate the cast — at JVM level it's an unchecked cast
        var casted = PlutusData.cast(data, PlutusData.ConstrData.class);
        assertInstanceOf(PlutusData.ConstrData.class, casted);
        assertEquals(0, casted.tag());
    }

    /**
     * Test 10: Cast chained with field access (e.g., PlutusData.cast(data, TxOut.class).value()).
     */
    @Test
    void castChainedFieldAccess() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    record Wrapper(BigInteger inner) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return PlutusData.cast(redeemer, Wrapper.class).inner() == 99;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();
        var redeemer = PlutusData.constr(0, PlutusData.integer(99));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0], alwaysInterval()),
                redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "Chained PlutusData.cast().field() should work. Got: " + result);
    }

    /**
     * Test 11: Explicit type declaration (non-var) — exercises the typeResolver.resolve(declType) path.
     */
    @Test
    void castWithExplicitType_works() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    record MyDatum(BigInteger amount) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        MyDatum datum = PlutusData.cast(redeemer, MyDatum.class);
                        return datum.amount() == 77;
                    }
                }
                """;
        var program = new JulcCompiler().compile(source).program();
        var redeemer = PlutusData.constr(0, PlutusData.integer(77));
        var ctx = buildScriptContext(
                buildTxInfo(new PlutusData[0], alwaysInterval()),
                redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), "Explicit type + PlutusData.cast should work. Got: " + result);
    }

    /**
     * Test 12: Non-ClassExpr second argument produces a compile error.
     * Uses a NameExpr (variable reference) as the second arg instead of a ClassExpr literal.
     */
    @Test
    void castWithNonClassExprArg_producesError() {
        var source = """
                import java.math.BigInteger;

                @Validator
                class TestValidator {
                    record MyDatum(BigInteger amount) {}

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        MyDatum datum = PlutusData.cast(redeemer, redeemer);
                        return datum.amount() == 42;
                    }
                }
                """;
        try {
            var result = new JulcCompiler().compileWithDetails(source);
            // If compileWithDetails returns (rather than throwing), check for collected errors
            assertTrue(result.hasErrors(),
                    "PlutusData.cast with non-ClassExpr second arg should produce an error");
            assertTrue(result.diagnostics().stream().anyMatch(e ->
                            e.message().contains("literal ClassName.class")),
                    "Error should mention literal ClassName.class requirement. Errors: " + result.diagnostics());
        } catch (CompilerException e) {
            // CompilerException with our message is also acceptable
            assertTrue(e.getMessage().contains("literal ClassName.class")
                            || e.getMessage().contains("PlutusData.cast"),
                    "Exception should relate to PlutusData.cast error. Got: " + e.getMessage());
        }
    }
}
