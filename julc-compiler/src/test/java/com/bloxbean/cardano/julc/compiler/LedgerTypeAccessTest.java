package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
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
    class LibraryLedgerTypes {

        static final StdlibRegistry STDLIB = StdlibRegistry.defaultRegistry();

        /**
         * Build a TxOut: Constr(0, [address, value, datum, refScript]).
         */
        static PlutusData buildTxOut(PlutusData address, PlutusData value) {
            var noOutputDatum = PlutusData.constr(0);  // NoOutputDatum
            var noRefScript = PlutusData.constr(1);    // None
            return PlutusData.constr(0, address, value, noOutputDatum, noRefScript);
        }

        /**
         * Build Address: Constr(0, [PubKeyCredential(pkh), noStaking]).
         */
        static PlutusData buildAddress(byte[] pkh) {
            var pubKeyCred = PlutusData.constr(0, PlutusData.bytes(pkh));
            var noStaking = PlutusData.constr(1);
            return PlutusData.constr(0, pubKeyCred, noStaking);
        }

        /**
         * Build a simple Value with only lovelace.
         */
        static PlutusData simpleValue(long lovelace) {
            return PlutusData.map(
                    new PlutusData.Pair(
                            PlutusData.bytes(new byte[0]),
                            PlutusData.map(
                                    new PlutusData.Pair(PlutusData.bytes(new byte[0]), PlutusData.integer(lovelace))
                            )
                    )
            );
        }

        @Test
        void libraryWithTxOutFieldAccess() {
            // Library method takes TxOut, accesses .address()
            var libSource = """
                    package com.example;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;

                    @OnchainLibrary
                    public class TestOutputLib {
                        public static boolean matchesAddress(TxOut txOut, Address expected) {
                            Address addr = txOut.address();
                            return addr == expected;
                        }
                    }
                    """;
            var validator = """
                    import com.example.TestOutputLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            // redeemer = Constr(0, [txOut, address])
                            var fields = Builtins.constrFields(redeemer);
                            TxOut txOut = Builtins.headList(fields);
                            Address addr = Builtins.headList(Builtins.tailList(fields));
                            return TestOutputLib.matchesAddress(txOut, addr);
                        }
                    }
                    """;
            var compiler = new JulcCompiler(STDLIB::lookup);
            var result = compiler.compile(validator, List.of(libSource));
            assertFalse(result.hasErrors(), "Compilation should succeed: " + result);

            // Build test data: TxOut with address, then compare to same address
            var pkh = new byte[]{1, 2, 3, 4, 5};
            var address = buildAddress(pkh);
            var value = simpleValue(2000000);
            var txOut = buildTxOut(address, value);
            var redeemer = PlutusData.constr(0, txOut, address);
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer,
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(ctx));
            assertTrue(evalResult.isSuccess(),
                    "Library TxOut field access should work. Got: " + evalResult);
        }

        @Test
        void libraryWithListTxOutForEach() {
            // Library method takes List<TxOut>, uses for-each to count
            var libSource = """
                    package com.example;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
                    import java.util.List;

                    @OnchainLibrary
                    public class TestOutputLib {
                        public static long countOutputs(List<TxOut> outputs) {
                            long count = 0;
                            for (TxOut out : outputs) {
                                count = count + 1;
                            }
                            return count;
                        }
                    }
                    """;
            var validator = """
                    import com.example.TestOutputLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            // redeemer = ListData of TxOuts
                            long count = TestOutputLib.countOutputs(Builtins.unListData(redeemer));
                            return count == 2;
                        }
                    }
                    """;
            var compiler = new JulcCompiler(STDLIB::lookup);
            var result = compiler.compile(validator, List.of(libSource));
            assertFalse(result.hasErrors(), "Compilation should succeed: " + result);

            var pkh = new byte[]{1, 2, 3};
            var address = buildAddress(pkh);
            var value = simpleValue(2000000);
            var txOut1 = buildTxOut(address, value);
            var txOut2 = buildTxOut(address, simpleValue(3000000));
            var redeemer = PlutusData.list(txOut1, txOut2);
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer,
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(ctx));
            assertTrue(evalResult.isSuccess(),
                    "Library for-each over List<TxOut> should work. Got: " + evalResult);
        }

        @Test
        void libraryAccessingValueField() {
            // Library method accesses TxOut.value() and extracts lovelace via Builtins
            var libSource = """
                    package com.example;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;

                    @OnchainLibrary
                    public class TestOutputLib {
                        public static long getLovelace(TxOut txOut) {
                            Value val = txOut.value();
                            var pairs = Builtins.unMapData(val);
                            var firstPair = Builtins.headList(pairs);
                            var tokenMapData = Builtins.sndPair(firstPair);
                            var tokenPairs = Builtins.unMapData(tokenMapData);
                            var firstTokenPair = Builtins.headList(tokenPairs);
                            return Builtins.unIData(Builtins.sndPair(firstTokenPair));
                        }
                    }
                    """;
            var validator = """
                    import com.example.TestOutputLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            // redeemer = a TxOut
                            long lovelace = TestOutputLib.getLovelace(redeemer);
                            return lovelace == 5000000;
                        }
                    }
                    """;
            var compiler = new JulcCompiler(STDLIB::lookup);
            var result = compiler.compile(validator, List.of(libSource));
            assertFalse(result.hasErrors(), "Compilation should succeed: " + result);

            var pkh = new byte[]{1, 2, 3};
            var address = buildAddress(pkh);
            var value = simpleValue(5000000);
            var txOut = buildTxOut(address, value);
            var ctx = PlutusData.constr(0, PlutusData.integer(0), txOut,
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(ctx));
            assertTrue(evalResult.isSuccess(),
                    "Library accessing txOut.value() and extracting lovelace should work. Got: " + evalResult);
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
