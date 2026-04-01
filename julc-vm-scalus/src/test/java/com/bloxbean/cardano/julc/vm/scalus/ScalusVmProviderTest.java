package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScalusVmProviderTest {

    private ScalusVmProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ScalusVmProvider();
    }

    @Test
    void providerMetadata() {
        assertEquals("Scalus", provider.name());
        assertEquals(50, provider.priority());
    }

    @Nested
    class BasicEvaluation {

        @Test
        void evaluateIntegerConstant() {
            // (program 1.1.0 (con integer 42))
            var program = Program.plutusV3(Term.const_(Constant.integer(42)));
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var success = (EvalResult.Success) result;
            assertInstanceOf(Term.Const.class, success.resultTerm());
            var c = (Term.Const) success.resultTerm();
            assertInstanceOf(Constant.IntegerConst.class, c.value());
            assertEquals(BigInteger.valueOf(42), ((Constant.IntegerConst) c.value()).value());
        }

        @Test
        void evaluateUnitConstant() {
            // (program 1.1.0 (con unit ()))
            var program = Program.plutusV3(Term.const_(Constant.unit()));
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var success = (EvalResult.Success) result;
            var c = (Term.Const) success.resultTerm();
            assertInstanceOf(Constant.UnitConst.class, c.value());
        }

        @Test
        void evaluateBoolConstant() {
            // (program 1.1.0 (con bool True))
            var program = Program.plutusV3(Term.const_(Constant.bool(true)));
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertTrue(((Constant.BoolConst) c.value()).value());
        }

        @Test
        void evaluateStringConstant() {
            // (program 1.1.0 (con string "hello"))
            var program = Program.plutusV3(Term.const_(Constant.string("hello")));
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals("hello", ((Constant.StringConst) c.value()).value());
        }

        @Test
        void evaluateByteStringConstant() {
            // (program 1.1.0 (con bytestring #deadbeef))
            var program = Program.plutusV3(Term.const_(Constant.byteString(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF})));
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
                    ((Constant.ByteStringConst) c.value()).value());
        }

        @Test
        void evaluateDataConstant() {
            // (program 1.1.0 (con data (I 42)))
            var program = Program.plutusV3(Term.const_(Constant.data(PlutusData.integer(42))));
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            var data = ((Constant.DataConst) c.value()).value();
            assertInstanceOf(PlutusData.IntData.class, data);
            assertEquals(BigInteger.valueOf(42), ((PlutusData.IntData) data).value());
        }
    }

    @Nested
    class BuiltinOperations {

        @Test
        void addInteger() {
            // (program 1.1.0 [[(builtin addInteger) (con integer 2)] (con integer 3)])
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(2))),
                    Term.const_(Constant.integer(3)));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(5), ((Constant.IntegerConst) c.value()).value());
        }

        @Test
        void subtractInteger() {
            // [[(builtin subtractInteger) (con integer 10)] (con integer 3)]
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.SubtractInteger),
                            Term.const_(Constant.integer(10))),
                    Term.const_(Constant.integer(3)));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) c.value()).value());
        }

        @Test
        void multiplyInteger() {
            // [[(builtin multiplyInteger) (con integer 6)] (con integer 7)]
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.MultiplyInteger),
                            Term.const_(Constant.integer(6))),
                    Term.const_(Constant.integer(7)));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(42), ((Constant.IntegerConst) c.value()).value());
        }

        @Test
        void equalsIntegerTrue() {
            // [[(builtin equalsInteger) (con integer 5)] (con integer 5)]
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.EqualsInteger),
                            Term.const_(Constant.integer(5))),
                    Term.const_(Constant.integer(5)));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertTrue(((Constant.BoolConst) c.value()).value());
        }

        @Test
        void equalsIntegerFalse() {
            // [[(builtin equalsInteger) (con integer 5)] (con integer 6)]
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.EqualsInteger),
                            Term.const_(Constant.integer(5))),
                    Term.const_(Constant.integer(6)));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertFalse(((Constant.BoolConst) c.value()).value());
        }

        @Test
        void appendByteString() {
            // [[(builtin appendByteString) (con bytestring #0102)] (con bytestring #0304)]
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.AppendByteString),
                            Term.const_(Constant.byteString(new byte[]{0x01, 0x02}))),
                    Term.const_(Constant.byteString(new byte[]{0x03, 0x04})));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04},
                    ((Constant.ByteStringConst) c.value()).value());
        }

        @Test
        void appendString() {
            // [[(builtin appendString) (con string "hello")] (con string " world")]
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.AppendString),
                            Term.const_(Constant.string("hello"))),
                    Term.const_(Constant.string(" world")));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals("hello world", ((Constant.StringConst) c.value()).value());
        }

        @Test
        void ifThenElseTrue() {
            // [[[force (builtin ifThenElse)] (con bool True)] (con integer 1)] (con integer 2)]
            var term = Term.apply(
                    Term.apply(
                            Term.apply(
                                    Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                    Term.const_(Constant.bool(true))),
                            Term.const_(Constant.integer(1))),
                    Term.const_(Constant.integer(2)));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(1), ((Constant.IntegerConst) c.value()).value());
        }

        @Test
        void ifThenElseFalse() {
            var term = Term.apply(
                    Term.apply(
                            Term.apply(
                                    Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                    Term.const_(Constant.bool(false))),
                            Term.const_(Constant.integer(1))),
                    Term.const_(Constant.integer(2)));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(2), ((Constant.IntegerConst) c.value()).value());
        }
    }

    @Nested
    class LambdaExpressions {

        @Test
        void identityFunction() {
            // (program 1.1.0 [(lam x x) (con integer 42)])
            var term = Term.apply(
                    Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                    Term.const_(Constant.integer(42)));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(42), ((Constant.IntegerConst) c.value()).value());
        }

        @Test
        void nestedLambda() {
            // (program 1.1.0 [[(lam a (lam b [[builtin addInteger] a b])) (con integer 3)] (con integer 4)])
            // a = DeBruijn 2 (skip b), b = DeBruijn 1
            var addBody = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.AddInteger),
                            Term.var(new NamedDeBruijn("a", 2))),
                    Term.var(new NamedDeBruijn("b", 1)));
            var term = Term.apply(
                    Term.apply(
                            Term.lam("a", Term.lam("b", addBody)),
                            Term.const_(Constant.integer(3))),
                    Term.const_(Constant.integer(4)));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) c.value()).value());
        }

        @Test
        void delayForce() {
            // (program 1.1.0 (force (delay (con integer 99))))
            var term = Term.force(Term.delay(Term.const_(Constant.integer(99))));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(99), ((Constant.IntegerConst) c.value()).value());
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void errorTermCausesFailure() {
            // (program 1.1.0 (error))
            var program = Program.plutusV3(Term.error());
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertFalse(result.isSuccess());
            assertInstanceOf(EvalResult.Failure.class, result);
        }

        @Test
        void errorInBranch() {
            // CEK machine is strict — all builtin args are evaluated before the builtin runs.
            // To avoid evaluating the error branch, use Delay/Force for lazy branching:
            // force [[[force (builtin ifThenElse)] (con bool True)] (delay (con integer 1))] (delay (error))]
            var term = Term.force(
                    Term.apply(
                            Term.apply(
                                    Term.apply(
                                            Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                            Term.const_(Constant.bool(true))),
                                    Term.delay(Term.const_(Constant.integer(1)))),
                            Term.delay(Term.error())));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            // True branch is picked, error never reached
            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(1), ((Constant.IntegerConst) c.value()).value());
        }
    }

    @Nested
    class TraceMessages {

        @Test
        void traceEmitsMessage() {
            // [[(force (builtin trace)) (con string "hello trace")] (con unit ())]
            var term = Term.apply(
                    Term.apply(
                            Term.force(Term.builtin(DefaultFun.Trace)),
                            Term.const_(Constant.string("hello trace"))),
                    Term.const_(Constant.unit()));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var success = (EvalResult.Success) result;
            assertTrue(success.traces().contains("hello trace"),
                    "Expected trace message 'hello trace' in " + success.traces());
        }

        @Test
        void multipleTraces() {
            // [[(force (builtin trace)) (con string "second")]
            //   [[(force (builtin trace)) (con string "first")] (con unit ())]]
            var traceFirst = Term.apply(
                    Term.apply(
                            Term.force(Term.builtin(DefaultFun.Trace)),
                            Term.const_(Constant.string("first"))),
                    Term.const_(Constant.unit()));
            var traceBoth = Term.apply(
                    Term.apply(
                            Term.force(Term.builtin(DefaultFun.Trace)),
                            Term.const_(Constant.string("second"))),
                    traceFirst);
            var program = Program.plutusV3(traceBoth);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var traces = ((EvalResult.Success) result).traces();
            assertEquals(2, traces.size());
            assertEquals("first", traces.get(0));
            assertEquals("second", traces.get(1));
        }
    }

    @Nested
    class EvaluateWithArgs {

        @Test
        void applyDataArguments() {
            // Program: identity function (lam x x)
            // Apply I 42 as Data argument → should return (con data (I 42))
            var identityProgram = Program.plutusV3(
                    Term.lam("x", Term.var(new NamedDeBruijn("x", 1))));
            var result = provider.evaluateWithArgs(
                    identityProgram, PlutusLanguage.PLUTUS_V3,
                    List.of(PlutusData.integer(42)), null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            var data = ((Constant.DataConst) c.value()).value();
            assertEquals(BigInteger.valueOf(42), ((PlutusData.IntData) data).value());
        }

        @Test
        void applyMultipleArgs() {
            // Program: (lam a (lam b [[(builtin addInteger) [(builtin unIData) a]] [(builtin unIData) b]]))
            // This unpacks two Data integers and adds them
            var addBody = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.AddInteger),
                            Term.apply(Term.builtin(DefaultFun.UnIData),
                                    Term.var(new NamedDeBruijn("a", 2)))),
                    Term.apply(Term.builtin(DefaultFun.UnIData),
                            Term.var(new NamedDeBruijn("b", 1))));
            var program = Program.plutusV3(
                    Term.lam("a", Term.lam("b", addBody)));

            var result = provider.evaluateWithArgs(
                    program, PlutusLanguage.PLUTUS_V3,
                    List.of(PlutusData.integer(10), PlutusData.integer(20)), null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(30), ((Constant.IntegerConst) c.value()).value());
        }
    }

    @Nested
    class BudgetTracking {

        @Test
        void successReportsBudget() {
            var program = Program.plutusV3(Term.const_(Constant.integer(1)));
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var budget = result.budgetConsumed();
            assertTrue(budget.cpuSteps() > 0, "Expected positive CPU steps");
            assertTrue(budget.memoryUnits() > 0, "Expected positive memory units");
        }

        @Test
        void failureReportsBudget() {
            var program = Program.plutusV3(Term.error());
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertFalse(result.isSuccess());
            // Budget should be non-negative
            assertTrue(result.budgetConsumed().cpuSteps() >= 0);
            assertTrue(result.budgetConsumed().memoryUnits() >= 0);
        }
    }

    @Nested
    class PlutusLanguageVersions {

        @Test
        void evaluateAsPlutusV1() {
            // Simple addition should work on V1 too
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(1))),
                    Term.const_(Constant.integer(2)));
            // V1 uses version 1.0.0
            var program = Program.plutusV1(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V1, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(3), ((Constant.IntegerConst) c.value()).value());
        }

        @Test
        void evaluateAsPlutusV2() {
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(10))),
                    Term.const_(Constant.integer(20)));
            var program = Program.plutusV2(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V2, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(30), ((Constant.IntegerConst) c.value()).value());
        }
    }

    @Nested
    class DataOperations {

        @Test
        void iData() {
            // [(builtin iData) (con integer 42)] → (con data (I 42))
            var term = Term.apply(
                    Term.builtin(DefaultFun.IData),
                    Term.const_(Constant.integer(42)));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            var data = ((Constant.DataConst) c.value()).value();
            assertInstanceOf(PlutusData.IntData.class, data);
            assertEquals(BigInteger.valueOf(42), ((PlutusData.IntData) data).value());
        }

        @Test
        void unIData() {
            // [(builtin unIData) (con data (I 42))] → (con integer 42)
            var term = Term.apply(
                    Term.builtin(DefaultFun.UnIData),
                    Term.const_(Constant.data(PlutusData.integer(42))));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(42), ((Constant.IntegerConst) c.value()).value());
        }

        @Test
        void bData() {
            // [(builtin bData) (con bytestring #cafe)] → (con data (B #cafe))
            var term = Term.apply(
                    Term.builtin(DefaultFun.BData),
                    Term.const_(Constant.byteString(new byte[]{(byte) 0xca, (byte) 0xfe})));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            var data = ((Constant.DataConst) c.value()).value();
            assertInstanceOf(PlutusData.BytesData.class, data);
            assertArrayEquals(new byte[]{(byte) 0xca, (byte) 0xfe},
                    ((PlutusData.BytesData) data).value());
        }

        @Test
        void equalsDataTrue() {
            // [[(builtin equalsData) (con data (I 42))] (con data (I 42))]
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.EqualsData),
                            Term.const_(Constant.data(PlutusData.integer(42)))),
                    Term.const_(Constant.data(PlutusData.integer(42))));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertTrue(((Constant.BoolConst) c.value()).value());
        }

        @Test
        void constrData() {
            // [[(builtin constrData) (con integer 0)]
            //   [(builtin mkNilData) (con unit ())]]
            var emptyList = Term.apply(
                    Term.builtin(DefaultFun.MkNilData),
                    Term.const_(Constant.unit()));
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.ConstrData),
                            Term.const_(Constant.integer(0))),
                    emptyList);
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            var data = ((Constant.DataConst) c.value()).value();
            assertInstanceOf(PlutusData.ConstrData.class, data);
            var constr = (PlutusData.ConstrData) data;
            assertEquals(0, constr.tag());
            assertTrue(constr.fields().isEmpty());
        }
    }

    @Nested
    class ConstrAndCase {

        @Test
        void constrCreatesTaggedValue() {
            // (constr 0 (con integer 1) (con integer 2))
            var term = new Term.Constr(0, List.of(
                    Term.const_(Constant.integer(1)),
                    Term.const_(Constant.integer(2))));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            assertInstanceOf(Term.Constr.class, ((EvalResult.Success) result).resultTerm());
            var constr = (Term.Constr) ((EvalResult.Success) result).resultTerm();
            assertEquals(0, constr.tag());
            assertEquals(2, constr.fields().size());
        }

        @Test
        void caseSelectsBranch() {
            // (case (constr 1 (con integer 42))
            //       (lam x (con integer 0))
            //       (lam x x))
            // Constructor tag=1 selects branch index 1 (the identity function)
            var scrutinee = new Term.Constr(1, List.of(Term.const_(Constant.integer(42))));
            var branch0 = Term.lam("x", Term.const_(Constant.integer(0)));
            var branch1 = Term.lam("x", Term.var(new NamedDeBruijn("x", 1)));
            var term = new Term.Case(scrutinee, List.of(branch0, branch1));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(42), ((Constant.IntegerConst) c.value()).value());
        }

        @Test
        void caseSelectsFirstBranch() {
            // (case (constr 0) (delay (con integer 99)))
            // Constructor tag=0, no fields — selects branch 0
            var scrutinee = new Term.Constr(0, List.of());
            var branch0 = Term.delay(Term.const_(Constant.integer(99)));
            var term = Term.force(new Term.Case(scrutinee, List.of(branch0)));
            var program = Program.plutusV3(term);
            var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(99), ((Constant.IntegerConst) c.value()).value());
        }
    }

    @Nested
    class CostModelParams {

        @Test
        void customMachineParams_usedByVm() {
            // Verify the machineParams field is wired through to createVm.
            // Directly set machineParams via Scalus API and confirm evaluation uses it.
            var customProvider = new ScalusVmProvider();
            var defaultMp = scalus.uplc.eval.MachineParams.defaultPlutusV3Params();
            // Invoke setCostModelParams indirectly by setting the field
            // (accessible since test is in same package).
            // Instead, use the Scalus API directly to set machineParams.
            customProvider.machineParams = defaultMp;
            customProvider.protocolVersion = scalus.cardano.ledger.MajorProtocolVersion.changPV();

            // Evaluate: 10 + 20 = 30
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(10))),
                    Term.const_(Constant.integer(20)));
            var program = Program.plutusV3(term);

            var result = customProvider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
            assertTrue(result.isSuccess());
            var c = (Term.Const) ((EvalResult.Success) result).resultTerm();
            assertEquals(BigInteger.valueOf(30), ((Constant.IntegerConst) c.value()).value());
            assertTrue(result.budgetConsumed().cpuSteps() > 0);
            assertTrue(result.budgetConsumed().memoryUnits() > 0);
        }

        @Test
        void customMachineParams_budgetMatchesDefault() {
            // Verify that using default params explicitly produces the same budget
            // as the no-params path.
            var term = Term.apply(
                    Term.apply(
                            Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(2))),
                    Term.const_(Constant.integer(3)));
            var program = Program.plutusV3(term);

            // Without custom params
            var defaultProvider = new ScalusVmProvider();
            var defaultResult = defaultProvider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            // With default params set explicitly
            var customProvider = new ScalusVmProvider();
            customProvider.machineParams = scalus.uplc.eval.MachineParams.defaultPlutusV3Params();
            customProvider.protocolVersion = scalus.cardano.ledger.MajorProtocolVersion.changPV();
            var customResult = customProvider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

            assertTrue(defaultResult.isSuccess());
            assertTrue(customResult.isSuccess());
            assertEquals(defaultResult.budgetConsumed().cpuSteps(),
                    customResult.budgetConsumed().cpuSteps());
            assertEquals(defaultResult.budgetConsumed().memoryUnits(),
                    customResult.budgetConsumed().memoryUnits());
        }

        @Test
        void defaultProvider_ignoresCostModelParams() {
            // The default JulcVmProvider.setCostModelParams should be a no-op.
            JulcVmProvider mockProvider = new JulcVmProvider() {
                @Override
                public EvalResult evaluate(Program p, PlutusLanguage l, ExBudget b, EvalOptions o) { return null; }
                @Override
                public EvalResult evaluateWithArgs(Program p, PlutusLanguage l, List<PlutusData> a, ExBudget b, EvalOptions o) { return null; }
                @Override
                public String name() { return "test"; }
                @Override
                public int priority() { return 0; }
            };
            // Should not throw
            mockProvider.setCostModelParams(new long[]{1, 2, 3}, PlutusLanguage.PLUTUS_V3, 10, 0);
        }
    }

    @Nested
    class ServiceLoaderDiscovery {

        @Test
        void plutusVmFindsScalusProvider() {
            var vm = JulcVm.create();
            assertEquals("Scalus", vm.providerName());
        }

        @Test
        void plutusVmEvaluatesViaServiceLoader() {
            var vm = JulcVm.create();
            var program = Program.plutusV3(Term.const_(Constant.integer(7)));
            var result = vm.evaluate(program);

            assertTrue(result.isSuccess());
        }
    }
}
