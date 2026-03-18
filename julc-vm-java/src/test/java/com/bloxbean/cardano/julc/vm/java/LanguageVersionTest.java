package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.builtins.BuiltinTable;
import com.bloxbean.cardano.julc.vm.java.builtins.UnsupportedBuiltinException;
import com.bloxbean.cardano.julc.vm.java.cost.CostModelParser;
import com.bloxbean.cardano.julc.vm.java.cost.DefaultCostModel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for V1/V2/V3 language version support in the Java VM.
 */
class LanguageVersionTest {

    private final JavaVmProvider provider = new JavaVmProvider();

    // ============================================================
    // Version gating: Constr/Case terms
    // ============================================================

    @Nested
    class ConstrCaseGating {

        @Test
        void v1_rejects_constr_term() {
            // Constr(0, [42]) — should fail for V1
            var term = new Term.Constr(0, List.of(Term.const_(Constant.integer(42))));
            var result = evaluate(term, PlutusLanguage.PLUTUS_V1);
            assertFailureContains(result, "Constr term is not available in PLUTUS_V1");
        }

        @Test
        void v2_rejects_constr_term() {
            var term = new Term.Constr(0, List.of(Term.const_(Constant.integer(42))));
            var result = evaluate(term, PlutusLanguage.PLUTUS_V2);
            assertFailureContains(result, "Constr term is not available in PLUTUS_V2");
        }

        @Test
        void v3_accepts_constr_term() {
            // Constr(0, []) — empty constructor, should succeed in V3
            var term = new Term.Constr(0, List.of());
            var result = evaluate(term, PlutusLanguage.PLUTUS_V3);
            assertInstanceOf(EvalResult.Success.class, result,
                    () -> "Expected success but got: " + result);
        }

        @Test
        void v1_rejects_case_term() {
            // case (constr 0 []) of { branch0 } — both Constr and Case should fail
            var constr = new Term.Constr(0, List.of());
            var term = new Term.Case(constr, List.of(Term.const_(Constant.integer(1))));
            var result = evaluate(term, PlutusLanguage.PLUTUS_V1);
            // Will fail on the Constr inside Case's scrutinee first
            assertInstanceOf(EvalResult.Failure.class, result);
        }

        @Test
        void v2_rejects_case_term() {
            // Use a non-Constr scrutinee that would still trigger Case check
            // case True of { ... } — V2 can't use Case
            var caseTerm = new Term.Case(
                    Term.const_(Constant.bool(true)),
                    List.of(Term.const_(Constant.integer(0)),
                            Term.const_(Constant.integer(1))));
            var result = evaluate(caseTerm, PlutusLanguage.PLUTUS_V2);
            assertFailureContains(result, "Case term is not available in PLUTUS_V2");
        }

        @Test
        void v3_accepts_case_on_bool() {
            // case True of { 0, 1 } → 1
            var caseTerm = new Term.Case(
                    Term.const_(Constant.bool(true)),
                    List.of(Term.const_(Constant.integer(0)),
                            Term.const_(Constant.integer(1))));
            var result = evaluate(caseTerm, PlutusLanguage.PLUTUS_V3);
            assertSuccess(result, 1);
        }
    }

    // ============================================================
    // Version gating: Builtin availability
    // ============================================================

    @Nested
    class BuiltinGating {

        @Test
        void v1_accepts_addInteger() {
            // AddInteger 1 2 → 3
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(1))),
                    Term.const_(Constant.integer(2)));
            var result = evaluate(term, PlutusLanguage.PLUTUS_V1);
            assertSuccess(result, 3);
        }

        @Test
        void v1_rejects_serialiseData() {
            // SerialiseData is V2 — should fail for V1
            var term = Term.apply(
                    Term.builtin(DefaultFun.SerialiseData),
                    Term.const_(Constant.data(new PlutusData.IntData(BigInteger.valueOf(42)))));
            var result = evaluate(term, PlutusLanguage.PLUTUS_V1);
            assertInstanceOf(EvalResult.Failure.class, result,
                    () -> "Expected failure for V2 builtin in V1, got: " + result);
        }

        @Test
        void v2_accepts_serialiseData() {
            // SerialiseData on integer 42 — should succeed in V2
            var term = Term.apply(
                    Term.builtin(DefaultFun.SerialiseData),
                    Term.const_(Constant.data(new PlutusData.IntData(BigInteger.valueOf(42)))));
            var result = evaluate(term, PlutusLanguage.PLUTUS_V2);
            assertInstanceOf(EvalResult.Success.class, result,
                    () -> "Expected success but got: " + result);
        }

        @Test
        void v1_rejects_verifyEcdsaSecp256k1() {
            // V2-only builtin — should fail for V1
            var table = BuiltinTable.forLanguage(PlutusLanguage.PLUTUS_V1);
            assertFalse(table.isSupported(DefaultFun.VerifyEcdsaSecp256k1Signature));
            assertThrows(UnsupportedBuiltinException.class,
                    () -> table.getSignature(DefaultFun.VerifyEcdsaSecp256k1Signature));
        }

        @Test
        void v2_rejects_bls12_381_g1_add() {
            // V3-only builtin — should fail for V2
            var table = BuiltinTable.forLanguage(PlutusLanguage.PLUTUS_V2);
            assertFalse(table.isSupported(DefaultFun.Bls12_381_G1_add));
            var ex = assertThrows(UnsupportedBuiltinException.class,
                    () -> table.getSignature(DefaultFun.Bls12_381_G1_add));
            assertTrue(ex.getMessage().contains("requires"));
        }

        @Test
        void v2_rejects_keccak256() {
            // V3-only builtin
            var table = BuiltinTable.forLanguage(PlutusLanguage.PLUTUS_V2);
            assertFalse(table.isSupported(DefaultFun.Keccak_256));
        }

        @Test
        void v3_accepts_all_v1v2v3_builtins() {
            var table = BuiltinTable.forLanguage(PlutusLanguage.PLUTUS_V3);
            for (var fun : DefaultFun.values()) {
                if (fun.minLanguageVersion() <= 3) {
                    assertTrue(table.isSupported(fun), "V3 table should support " + fun);
                }
            }
        }
    }

    // ============================================================
    // DefaultFun.minLanguageVersion() and isAvailableIn()
    // ============================================================

    @Nested
    class DefaultFunVersionMetadata {

        @Test
        void v1_builtins_have_version_1() {
            assertEquals(1, DefaultFun.AddInteger.minLanguageVersion());
            assertEquals(1, DefaultFun.MkNilPairData.minLanguageVersion()); // last V1 (code 50)
        }

        @Test
        void v2_builtins_have_version_2() {
            assertEquals(2, DefaultFun.SerialiseData.minLanguageVersion());
            assertEquals(2, DefaultFun.VerifyEcdsaSecp256k1Signature.minLanguageVersion());
            assertEquals(2, DefaultFun.VerifySchnorrSecp256k1Signature.minLanguageVersion());
        }

        @Test
        void v3_builtins_have_version_3() {
            assertEquals(3, DefaultFun.Bls12_381_G1_add.minLanguageVersion());
            assertEquals(3, DefaultFun.ExpModInteger.minLanguageVersion()); // last V3 (code 87)
        }

        @Test
        void v4_builtins_have_version_4() {
            assertEquals(4, DefaultFun.DropList.minLanguageVersion());
            assertEquals(4, DefaultFun.MultiIndexArray.minLanguageVersion());
        }

        @Test
        void isAvailableIn_checks() {
            assertTrue(DefaultFun.AddInteger.isAvailableIn(1));
            assertTrue(DefaultFun.AddInteger.isAvailableIn(3));
            assertFalse(DefaultFun.SerialiseData.isAvailableIn(1));
            assertTrue(DefaultFun.SerialiseData.isAvailableIn(2));
            assertTrue(DefaultFun.SerialiseData.isAvailableIn(3));
            assertFalse(DefaultFun.Bls12_381_G1_add.isAvailableIn(2));
            assertTrue(DefaultFun.Bls12_381_G1_add.isAvailableIn(3));
        }
    }

    // ============================================================
    // BuiltinTable version counts
    // ============================================================

    @Nested
    class BuiltinTableVersionCounts {

        @Test
        void v1_table_has_51_builtins() {
            var table = BuiltinTable.forLanguage(PlutusLanguage.PLUTUS_V1);
            int count = 0;
            for (var fun : DefaultFun.values()) {
                if (table.isSupported(fun)) count++;
            }
            assertEquals(51, count, "V1 should have 51 builtins");
        }

        @Test
        void v2_table_has_54_builtins() {
            var table = BuiltinTable.forLanguage(PlutusLanguage.PLUTUS_V2);
            int count = 0;
            for (var fun : DefaultFun.values()) {
                if (table.isSupported(fun)) count++;
            }
            assertEquals(54, count, "V2 should have 54 builtins (51 V1 + 3 V2)");
        }

        @Test
        void v3_table_has_88_builtins() {
            var table = BuiltinTable.forLanguage(PlutusLanguage.PLUTUS_V3);
            int count = 0;
            for (var fun : DefaultFun.values()) {
                if (table.isSupported(fun)) count++;
            }
            assertEquals(88, count, "V3 should have 88 builtins (54 V2 + 34 V3)");
        }
    }

    // ============================================================
    // V1/V2 evaluation with full programs
    // ============================================================

    @Nested
    class V1V2Evaluation {

        @Test
        void v1_evaluates_simple_addition() {
            // 2 + 3 = 5 — pure V1 program
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(2))),
                    Term.const_(Constant.integer(3)));
            var result = evaluate(term, PlutusLanguage.PLUTUS_V1);
            assertSuccess(result, 5);
        }

        @Test
        void v1_evaluates_lambda_with_builtin() {
            // (\x -> x + x) 21 → 42
            var addxx = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger), Term.var(1)),
                    Term.var(1));
            var term = Term.apply(Term.lam("x", addxx), Term.const_(Constant.integer(21)));
            var result = evaluate(term, PlutusLanguage.PLUTUS_V1);
            assertSuccess(result, 42);
        }

        @Test
        void v2_evaluates_with_v2_builtin() {
            // SerialiseData on integer 7 should produce non-empty bytestring
            var term = Term.apply(
                    Term.builtin(DefaultFun.SerialiseData),
                    Term.const_(Constant.data(new PlutusData.IntData(BigInteger.valueOf(7)))));
            var result = evaluate(term, PlutusLanguage.PLUTUS_V2);
            assertInstanceOf(EvalResult.Success.class, result);
            var success = (EvalResult.Success) result;
            assertInstanceOf(Term.Const.class, success.resultTerm());
            var c = ((Term.Const) success.resultTerm()).value();
            assertInstanceOf(Constant.ByteStringConst.class, c);
            assertTrue(((Constant.ByteStringConst) c).value().length > 0);
        }

        @Test
        void v1_evaluates_ifThenElse() {
            // if True then 1 else 0 → 1
            var term = Term.force(
                    Term.apply(
                            Term.apply(
                                    Term.apply(
                                            Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                            Term.const_(Constant.bool(true))),
                                    Term.delay(Term.const_(Constant.integer(1)))),
                            Term.delay(Term.const_(Constant.integer(0)))));
            var result = evaluate(term, PlutusLanguage.PLUTUS_V1);
            assertSuccess(result, 1);
        }

        @Test
        void v1_evaluates_data_operations() {
            // IData(42) → Data, then UnIData → 42
            var idata = Term.apply(Term.builtin(DefaultFun.IData),
                    Term.const_(Constant.integer(42)));
            var term = Term.apply(Term.builtin(DefaultFun.UnIData), idata);
            var result = evaluate(term, PlutusLanguage.PLUTUS_V1);
            assertSuccess(result, 42);
        }

        @Test
        void v2_evaluates_v1_builtins() {
            // V2 can still use V1 builtins: 10 * 5 = 50
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.MultiplyInteger),
                            Term.const_(Constant.integer(10))),
                    Term.const_(Constant.integer(5)));
            var result = evaluate(term, PlutusLanguage.PLUTUS_V2);
            assertSuccess(result, 50);
        }
    }

    // ============================================================
    // Cost model version awareness
    // ============================================================

    @Nested
    class CostModelVersions {

        @Test
        void v1_default_machine_costs_have_zero_constr_case() {
            var mc = DefaultCostModel.defaultMachineCosts(PlutusLanguage.PLUTUS_V1);
            assertEquals(0, mc.constrCpu());
            assertEquals(0, mc.constrMem());
            assertEquals(0, mc.caseCpu());
            assertEquals(0, mc.caseMem());
            // V1 still has the 8 shared step types
            assertTrue(mc.varCpu() > 0);
            assertTrue(mc.lamCpu() > 0);
        }

        @Test
        void v2_default_machine_costs_have_zero_constr_case() {
            var mc = DefaultCostModel.defaultMachineCosts(PlutusLanguage.PLUTUS_V2);
            assertEquals(0, mc.constrCpu());
            assertEquals(0, mc.constrMem());
        }

        @Test
        void v3_default_machine_costs_have_nonzero_constr_case() {
            var mc = DefaultCostModel.defaultMachineCosts(PlutusLanguage.PLUTUS_V3);
            assertTrue(mc.constrCpu() > 0);
            assertTrue(mc.caseCpu() > 0);
        }

        @Test
        void v1_builtin_cost_model_excludes_v2_builtins() {
            var bcm = DefaultCostModel.defaultBuiltinCostModel(PlutusLanguage.PLUTUS_V1);
            assertNull(bcm.get(DefaultFun.SerialiseData), "V1 cost model should not have SerialiseData");
            assertNotNull(bcm.get(DefaultFun.AddInteger), "V1 cost model should have AddInteger");
        }

        @Test
        void v2_builtin_cost_model_excludes_v3_builtins() {
            var bcm = DefaultCostModel.defaultBuiltinCostModel(PlutusLanguage.PLUTUS_V2);
            assertNull(bcm.get(DefaultFun.Bls12_381_G1_add), "V2 cost model should not have BLS builtins");
            assertNotNull(bcm.get(DefaultFun.SerialiseData), "V2 cost model should have SerialiseData");
        }
    }

    // ============================================================
    // CostModelParser version-aware parsing
    // ============================================================

    @Nested
    class CostModelParsing {

        @Test
        void v1_parser_rejects_too_short_array() {
            assertThrows(IllegalArgumentException.class,
                    () -> CostModelParser.parse(new long[100], PlutusLanguage.PLUTUS_V1));
        }

        @Test
        void v2_parser_rejects_too_short_array() {
            assertThrows(IllegalArgumentException.class,
                    () -> CostModelParser.parse(new long[100], PlutusLanguage.PLUTUS_V2));
        }

        @Test
        void v1_parser_accepts_correct_length() {
            long[] values = new long[CostModelParser.V1_PARAM_COUNT];
            // Set machine costs at indices 17-32 to reasonable values
            for (int i = 17; i <= 32; i++) {
                values[i] = 16000 + (i - 17) * 100;
            }
            var parsed = CostModelParser.parse(values, PlutusLanguage.PLUTUS_V1);
            assertNotNull(parsed);
            assertNotNull(parsed.machineCosts());
            assertNotNull(parsed.builtinCostModel());
            // Constr/case should be 0
            assertEquals(0, parsed.machineCosts().constrCpu());
            assertEquals(0, parsed.machineCosts().caseCpu());
        }

        @Test
        void v2_parser_accepts_correct_length() {
            long[] values = new long[CostModelParser.V2_PARAM_COUNT];
            for (int i = 17; i <= 32; i++) {
                values[i] = 16000 + (i - 17) * 100;
            }
            var parsed = CostModelParser.parse(values, PlutusLanguage.PLUTUS_V2);
            assertNotNull(parsed);
            assertEquals(0, parsed.machineCosts().constrCpu());
        }

        @Test
        void v1_parser_extracts_machine_costs() {
            long[] values = new long[CostModelParser.V1_PARAM_COUNT];
            // Set specific machine costs — alphabetical order:
            // apply(17-18), builtin(19-20), const(21-22), delay(23-24),
            // force(25-26), lam(27-28), startup(29-30), var(31-32)
            values[17] = 23000;  // applyCpu
            values[18] = 100;    // applyMem
            values[29] = 200;    // startupCpu
            values[30] = 50;     // startupMem
            values[31] = 15000;  // varCpu
            values[32] = 80;     // varMem

            var parsed = CostModelParser.parse(values, PlutusLanguage.PLUTUS_V1);
            assertEquals(23000, parsed.machineCosts().applyCpu());
            assertEquals(100, parsed.machineCosts().applyMem());
            assertEquals(200, parsed.machineCosts().startupCpu());
            assertEquals(50, parsed.machineCosts().startupMem());
            assertEquals(15000, parsed.machineCosts().varCpu());
            assertEquals(80, parsed.machineCosts().varMem());
        }

        @Test
        void v3_parser_still_works_via_dispatch() {
            // Parse a V3 array via the language-aware method
            long[] values = CostModelParser.defaultToFlatArray();
            var parsed = CostModelParser.parse(values, PlutusLanguage.PLUTUS_V3);
            assertNotNull(parsed);
            assertTrue(parsed.machineCosts().constrCpu() > 0);
        }
    }

    // ============================================================
    // JavaVmProvider per-version cost model
    // ============================================================

    @Nested
    class ProviderVersionSupport {

        @Test
        void provider_evaluates_v1() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(100))),
                    Term.const_(Constant.integer(200)));
            var result = provider.evaluate(
                    Program.plutusV1(term), PlutusLanguage.PLUTUS_V1, null);
            assertSuccess(result, 300);
        }

        @Test
        void provider_evaluates_v2() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(100))),
                    Term.const_(Constant.integer(200)));
            var result = provider.evaluate(
                    Program.plutusV2(term), PlutusLanguage.PLUTUS_V2, null);
            assertSuccess(result, 300);
        }

        @Test
        void provider_setCostModelParams_per_version() {
            // Set V1 cost model
            long[] v1Params = new long[CostModelParser.V1_PARAM_COUNT];
            for (int i = 17; i <= 32; i++) {
                v1Params[i] = 16000;
            }
            provider.setCostModelParams(v1Params, PlutusLanguage.PLUTUS_V1);

            // V1 evaluation should work
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(1))),
                    Term.const_(Constant.integer(2)));
            var result = provider.evaluate(
                    Program.plutusV1(term), PlutusLanguage.PLUTUS_V1, null);
            assertSuccess(result, 3);
        }

        @Test
        void provider_evaluateWithArgs_v1() {
            // Simple validator: \datum -> \redeemer -> \ctx -> datum + redeemer
            var body = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.apply(Term.builtin(DefaultFun.UnIData), Term.var(3))),
                    Term.apply(Term.builtin(DefaultFun.UnIData), Term.var(2)));
            var validator = Term.lam("datum", Term.lam("redeemer", Term.lam("ctx", body)));

            var result = provider.evaluateWithArgs(
                    Program.plutusV1(validator),
                    PlutusLanguage.PLUTUS_V1,
                    List.of(
                            new PlutusData.IntData(BigInteger.valueOf(10)),
                            new PlutusData.IntData(BigInteger.valueOf(20)),
                            new PlutusData.IntData(BigInteger.ZERO)),
                    null);
            assertSuccess(result, 30);
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private EvalResult evaluate(Term term, PlutusLanguage language) {
        var program = switch (language) {
            case PLUTUS_V1 -> Program.plutusV1(term);
            case PLUTUS_V2 -> Program.plutusV2(term);
            case PLUTUS_V3 -> Program.plutusV3(term);
        };
        return provider.evaluate(program, language, null);
    }

    private void assertSuccess(EvalResult result, long expected) {
        assertInstanceOf(EvalResult.Success.class, result,
                () -> "Expected success but got: " + result);
        var success = (EvalResult.Success) result;
        assertInstanceOf(Term.Const.class, success.resultTerm());
        var c = ((Term.Const) success.resultTerm()).value();
        assertInstanceOf(Constant.IntegerConst.class, c);
        assertEquals(BigInteger.valueOf(expected), ((Constant.IntegerConst) c).value());
    }

    private void assertFailureContains(EvalResult result, String expectedMessage) {
        assertInstanceOf(EvalResult.Failure.class, result,
                () -> "Expected failure but got: " + result);
        var failure = (EvalResult.Failure) result;
        assertTrue(failure.error().contains(expectedMessage),
                () -> "Expected error to contain '" + expectedMessage + "' but was: " + failure.error());
    }
}
