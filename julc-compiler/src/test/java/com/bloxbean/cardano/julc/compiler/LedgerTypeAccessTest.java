package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.StdlibLookup;
import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for typed ledger access, list instance methods, and custom datum/redeemer records
 * with ledger type fields.
 */
class LedgerTypeAccessTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    /**
     * Build a minimal but valid V3 ScriptContext for testing.
     * ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
     */
    static PlutusData buildScriptContext(PlutusData txInfo, PlutusData redeemer, PlutusData scriptInfo) {
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    /**
     * Build a minimal TxInfo with signatories and valid range.
     * All 16 fields must be present for correct indexing.
     */
    static PlutusData buildTxInfo(PlutusData[] signatories, PlutusData validRange) {
        return PlutusData.constr(0,
                PlutusData.list(),                                     // 0: inputs
                PlutusData.list(),                                     // 1: referenceInputs
                PlutusData.list(),                                     // 2: outputs
                PlutusData.integer(2000000),                           // 3: fee (lovelace)
                PlutusData.map(),                                      // 4: mint (empty)
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

    /**
     * Build an "always" interval: Interval(IntervalBound(NegInf, True), IntervalBound(PosInf, True))
     * IntervalBound = Constr(0, [boundType, isInclusive])
     * NegInf = Constr(0, []), PosInf = Constr(2, [])
     * Bool True = Constr(1, [])
     */
    static PlutusData alwaysInterval() {
        var negInf = PlutusData.constr(0);
        var posInf = PlutusData.constr(2);
        var trueVal = PlutusData.constr(1);
        var lowerBound = PlutusData.constr(0, negInf, trueVal);
        var upperBound = PlutusData.constr(0, posInf, trueVal);
        return PlutusData.constr(0, lowerBound, upperBound);
    }

    /**
     * Stdlib lookup that provides ContextsLib functions for backward-compat tests.
     */
    static StdlibLookup testStdlibLookup() {
        return (className, methodName, args) -> {
            if ("ContextsLib".equals(className) && "getTxInfo".equals(methodName) && args.size() == 1) {
                var fields = new PirTerm.App(
                        new PirTerm.Builtin(DefaultFun.SndPair),
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), args.get(0)));
                return Optional.of(new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), fields));
            }
            if ("ContextsLib".equals(className) && "signedBy".equals(methodName) && args.size() == 2) {
                // Inline signedBy: extract signatories (field 8 of TxInfo), recursive search
                var txInfoVar = new PirTerm.Var("txInfo_sb", new com.bloxbean.cardano.julc.compiler.pir.PirType.DataType());
                var pkhVar = new PirTerm.Var("pkh_sb", new com.bloxbean.cardano.julc.compiler.pir.PirType.DataType());
                var fieldsExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair),
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), txInfoVar));
                // Index 8 = signatories
                PirTerm sigsData = fieldsExpr;
                for (int i = 0; i < 8; i++) sigsData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), sigsData);
                sigsData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), sigsData);
                var sigsExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), sigsData);
                var pkhBs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), pkhVar);
                // Recursive search
                var sigListVar = new PirTerm.Var("sigList_sb", new com.bloxbean.cardano.julc.compiler.pir.PirType.ListType(new com.bloxbean.cardano.julc.compiler.pir.PirType.DataType()));
                var targetVar = new PirTerm.Var("target_sb", new com.bloxbean.cardano.julc.compiler.pir.PirType.ByteStringType());
                var goVar = new PirTerm.Var("go_sb", new com.bloxbean.cardano.julc.compiler.pir.PirType.FunType(
                        new com.bloxbean.cardano.julc.compiler.pir.PirType.ListType(new com.bloxbean.cardano.julc.compiler.pir.PirType.DataType()),
                        new com.bloxbean.cardano.julc.compiler.pir.PirType.BoolType()));
                var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), sigListVar);
                var headElem = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), sigListVar);
                var tailExpr2 = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), sigListVar);
                var headBs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), headElem);
                var equalCheck = new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsByteString), targetVar), headBs);
                var recurse = new PirTerm.App(goVar, tailExpr2);
                var ifFound = new PirTerm.IfThenElse(equalCheck, new PirTerm.Const(Constant.bool(true)), recurse);
                var body = new PirTerm.IfThenElse(nullCheck, new PirTerm.Const(Constant.bool(false)), ifFound);
                var goBody = new PirTerm.Lam("sigList_sb", new com.bloxbean.cardano.julc.compiler.pir.PirType.ListType(new com.bloxbean.cardano.julc.compiler.pir.PirType.DataType()), body);
                var sigsVar = new PirTerm.Var("sigs_sb", new com.bloxbean.cardano.julc.compiler.pir.PirType.ListType(new com.bloxbean.cardano.julc.compiler.pir.PirType.DataType()));
                var search = new PirTerm.LetRec(java.util.List.of(new PirTerm.Binding("go_sb", goBody)),
                        new PirTerm.App(goVar, sigsVar));
                return Optional.of(new PirTerm.Let("txInfo_sb", args.get(0),
                        new PirTerm.Let("pkh_sb", args.get(1),
                                new PirTerm.Let("sigs_sb", sigsExpr,
                                        new PirTerm.Let("target_sb", pkhBs, search)))));
            }
            return Optional.empty();
        };
    }

    @Nested
    class ScriptContextFieldAccess {
        @Test
        void compileCtxTxInfo() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            return true;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var result = compiler.compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors());
        }

        @Test
        void evalCtxTxInfoFieldAccess() {
            // Validator that accesses ctx.txInfo().fee() and checks fee > 0
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            BigInteger fee = txInfo.fee();
                            return fee > 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var txInfo = buildTxInfo(new PlutusData[0], alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "fee=2000000 > 0 should succeed. Got: " + result);
        }
    }

    @Nested
    class TxInfoFieldAccess {
        @Test
        void evalTxInfoSignatories() {
            // Access txInfo.signatories() — should return a list
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            return sigs.isEmpty();
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // Empty signatories
            var txInfo = buildTxInfo(new PlutusData[0], alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Empty sigs .isEmpty() should be true. Got: " + result);
        }

        @Test
        void evalTxInfoValidRange() {
            // Access txInfo.validRange() — should return an Interval (RecordType)
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Interval range = txInfo.validRange();
                            return true;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var result = compiler.compile(source);
            assertNotNull(result.program());
        }

        @Test
        void evalTxInfoId() {
            // Access txInfo.id() — should return ByteString
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            byte[] txId = txInfo.id();
                            return true;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var result = compiler.compile(source);
            assertNotNull(result.program());
        }
    }

    @Nested
    class ListInstanceMethods {
        @Test
        void evalListContains() {
            // signatories.contains(pkh)
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            return sigs.contains(redeemer);
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var pkh = new byte[]{1, 2, 3, 4, 5};
            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(pkh)},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.bytes(pkh),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "contains(pkh) should find the signer. Got: " + result);
        }

        @Test
        void evalListContainsNotFound() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            return sigs.contains(redeemer);
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var pkh = new byte[]{1, 2, 3};
            var otherPkh = new byte[]{9, 8, 7};
            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(otherPkh)},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.bytes(pkh),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertFalse(result.isSuccess(), "contains(pkh) should not find non-signer. Got: " + result);
        }

        @Test
        void evalListSize() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            return sigs.size() == 2;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(new byte[]{1}), PlutusData.bytes(new byte[]{2})},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "size() should return 2. Got: " + result);
        }

        @Test
        void evalListIsEmpty() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            return !sigs.isEmpty();
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(new byte[]{1})},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "!isEmpty() should be true for non-empty list. Got: " + result);
        }
    }

    @Nested
    class ChainedMethodCalls {
        @Test
        void evalChainedContains() {
            // txInfo.signatories().contains(pkh) — chained without intermediate var
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            return txInfo.signatories().contains(redeemer);
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var pkh = new byte[]{1, 2, 3};
            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(pkh)},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.bytes(pkh),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Chained contains should work. Got: " + result);
        }

        @Test
        void evalChainedIsEmpty() {
            // txInfo.signatories().isEmpty()
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            return txInfo.signatories().isEmpty();
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var txInfo = buildTxInfo(new PlutusData[0], alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "isEmpty() on empty list should succeed. Got: " + result);
        }
    }

    @Nested
    class CustomDatumWithLedgerTypes {
        @Test
        void evalCustomDatumByteArray() {
            // Custom datum with byte[] and BigInteger fields — already works, verify it still does
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

                        @Entrypoint
                        static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            BigInteger d = datum.deadline();
                            return d == 1000;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // datum = Constr(0, [BData(beneficiary), IData(1000)])
            var datum = PlutusData.constr(0,
                    PlutusData.bytes(new byte[]{1, 2, 3}),
                    PlutusData.integer(1000));

            // Build spending ScriptContext with datum
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0,
                    PlutusData.bytes(new byte[32]),
                    PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var txInfo = buildTxInfo(new PlutusData[0], alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0), scriptInfo);

            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Custom datum deadline=1000 should match. Got: " + result);
        }

        @Test
        void compileCustomDatumWithLedgerType() {
            // Custom datum with PubKeyHash field (resolves to ByteStringType)
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record MyDatum(PubKeyHash owner, BigInteger deadline) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            byte[] ownerPkh = datum.owner();
                            BigInteger d = datum.deadline();
                            return d > 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var result = compiler.compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors());
        }
    }

    @Nested
    class BackwardCompatibility {
        @Test
        void plutusDataCtxStillWorks() {
            // Old-style validator with PlutusData ctx should still compile
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return true;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var result = compiler.compile(source);
            assertNotNull(result.program());
        }

        @Test
        void evalPlutusDataCtxStillWorks() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return true;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var ctx = PlutusData.constr(0,
                    PlutusData.integer(0),
                    PlutusData.integer(0),
                    PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Old-style validator should still succeed. Got: " + result);
        }

        @Test
        void stdlibContextsLibStillWorks() {
            // Using ContextsLib.signedBy with typed txInfo parameter
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            return ContextsLib.signedBy(txInfo, redeemer);
                        }
                    }
                    """;
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry()::lookup);
            var program = compiler.compile(source).program();

            var pkh = new byte[]{1, 2, 3, 4, 5};
            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(pkh)},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.bytes(pkh),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "ContextsLib.signedBy should work with typed TxInfo. Got: " + result);
        }
    }

    @Nested
    class FullValidator {
        @Test
        void realisticVestingValidator() {
            // A realistic vesting validator using typed access
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class VestingValidator {
                        record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

                        @Entrypoint
                        static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            boolean hasSigner = sigs.contains(datum.beneficiary());
                            return hasSigner;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var beneficiary = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
            var datum = PlutusData.constr(0,
                    PlutusData.bytes(beneficiary),
                    PlutusData.integer(1000));

            // Build spending ScriptContext
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0,
                    PlutusData.bytes(new byte[32]),
                    PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(beneficiary)},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0), scriptInfo);

            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Vesting validator should pass with correct signer. Got: " + result);
        }

        @Test
        void realisticVestingValidatorRejects() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class VestingValidator {
                        record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

                        @Entrypoint
                        static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            boolean hasSigner = sigs.contains(datum.beneficiary());
                            return hasSigner;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var beneficiary = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
            var wrongSigner = new byte[]{0x09, 0x08, 0x07};
            var datum = PlutusData.constr(0,
                    PlutusData.bytes(beneficiary),
                    PlutusData.integer(1000));

            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0,
                    PlutusData.bytes(new byte[32]),
                    PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(wrongSigner)},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0), scriptInfo);

            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertFalse(result.isSuccess(), "Vesting validator should reject with wrong signer. Got: " + result);
        }
    }
}
