package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the plutus-testkit module.
 * <p>
 * Validates that ValidatorTest, BudgetAssertions, and TestDataBuilder
 * all work correctly end-to-end with a real VM backend.
 */
class TestkitTest {

    // -------------------------------------------------------------------------
    // ValidatorTest: evaluate and assert
    // -------------------------------------------------------------------------

    @Nested
    class ValidatorTestTests {

        @Test
        void evaluateSimpleProgramSucceeds() {
            // A trivial program that just returns unit
            var program = Program.plutusV3(Term.const_(Constant.unit()));
            var result = ValidatorTest.evaluate(program);
            assertTrue(result.isSuccess(), "Expected success: " + result);
        }

        @Test
        void evaluateErrorProgramFails() {
            // A program that immediately errors
            var program = Program.plutusV3(Term.error());
            var result = ValidatorTest.evaluate(program);
            assertFalse(result.isSuccess(), "Expected failure for error term");
        }

        @Test
        void evaluateProgramWithDataArgs() {
            // Program: \x -> x (identity), applied to an integer data arg
            var identity = Term.lam("x", Term.var(1));
            var program = Program.plutusV3(identity);
            var result = ValidatorTest.evaluate(program, PlutusData.integer(42));
            assertTrue(result.isSuccess(), "Expected success: " + result);
        }

        @Test
        void assertValidatesSucceeds() {
            var program = Program.plutusV3(Term.const_(Constant.unit()));
            assertDoesNotThrow(() -> ValidatorTest.assertValidates(program));
        }

        @Test
        void assertValidatesThrowsOnFailure() {
            var program = Program.plutusV3(Term.error());
            assertThrows(AssertionError.class, () -> ValidatorTest.assertValidates(program));
        }

        @Test
        void assertRejectsSucceeds() {
            var program = Program.plutusV3(Term.error());
            assertDoesNotThrow(() -> ValidatorTest.assertRejects(program));
        }

        @Test
        void assertRejectsThrowsOnSuccess() {
            var program = Program.plutusV3(Term.const_(Constant.unit()));
            assertThrows(AssertionError.class, () -> ValidatorTest.assertRejects(program));
        }
    }

    // -------------------------------------------------------------------------
    // BudgetAssertions
    // -------------------------------------------------------------------------

    @Nested
    class BudgetAssertionTests {

        @Test
        void assertSuccessOnSuccessResult() {
            var program = Program.plutusV3(Term.const_(Constant.unit()));
            var result = ValidatorTest.evaluate(program);
            assertDoesNotThrow(() -> BudgetAssertions.assertSuccess(result));
        }

        @Test
        void assertSuccessThrowsOnFailure() {
            var program = Program.plutusV3(Term.error());
            var result = ValidatorTest.evaluate(program);
            assertThrows(AssertionError.class, () -> BudgetAssertions.assertSuccess(result));
        }

        @Test
        void assertFailureOnFailureResult() {
            var program = Program.plutusV3(Term.error());
            var result = ValidatorTest.evaluate(program);
            assertDoesNotThrow(() -> BudgetAssertions.assertFailure(result));
        }

        @Test
        void assertFailureThrowsOnSuccess() {
            var program = Program.plutusV3(Term.const_(Constant.unit()));
            var result = ValidatorTest.evaluate(program);
            assertThrows(AssertionError.class, () -> BudgetAssertions.assertFailure(result));
        }

        @Test
        void assertBudgetUnderPasses() {
            // A trivial program should consume very little budget
            var program = Program.plutusV3(Term.const_(Constant.unit()));
            var result = ValidatorTest.evaluate(program);
            // Use very generous limits
            assertDoesNotThrow(() ->
                    BudgetAssertions.assertBudgetUnder(result, 10_000_000_000L, 10_000_000_000L));
        }

        @Test
        void assertBudgetUnderFailsWhenExceeded() {
            // Evaluate an addition which consumes some budget
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(BigInteger.ONE))),
                    Term.const_(Constant.integer(BigInteger.TWO)));
            var program = Program.plutusV3(term);
            var result = ValidatorTest.evaluate(program);
            // Use impossibly low limits
            assertThrows(AssertionError.class, () ->
                    BudgetAssertions.assertBudgetUnder(result, 0, 0));
        }
    }

    // -------------------------------------------------------------------------
    // Trace assertions
    // -------------------------------------------------------------------------

    @Nested
    class TraceAssertionTests {

        @Test
        void assertTraceFindsExpectedMessage() {
            // Force(Trace) "hello" unit  -- logs "hello" and returns unit
            var term = Term.apply(
                    Term.apply(
                            Term.force(Term.builtin(DefaultFun.Trace)),
                            Term.const_(Constant.string("hello"))),
                    Term.const_(Constant.unit()));
            var program = Program.plutusV3(term);
            var result = ValidatorTest.evaluate(program);

            assertDoesNotThrow(() -> BudgetAssertions.assertTrace(result, "hello"));
        }

        @Test
        void assertTraceFailsWhenMessageMissing() {
            var program = Program.plutusV3(Term.const_(Constant.unit()));
            var result = ValidatorTest.evaluate(program);

            assertThrows(AssertionError.class, () ->
                    BudgetAssertions.assertTrace(result, "not present"));
        }

        @Test
        void assertNoTracesPassesWhenEmpty() {
            var program = Program.plutusV3(Term.const_(Constant.unit()));
            var result = ValidatorTest.evaluate(program);
            assertDoesNotThrow(() -> BudgetAssertions.assertNoTraces(result));
        }
    }

    // -------------------------------------------------------------------------
    // TestDataBuilder
    // -------------------------------------------------------------------------

    @Nested
    class TestDataBuilderTests {

        @Test
        void randomPubKeyHashProduces28Bytes() {
            var pkh = TestDataBuilder.randomPubKeyHash();
            assertInstanceOf(PlutusData.BytesData.class, pkh);
            assertEquals(28, ((PlutusData.BytesData) pkh).value().length);
        }

        @Test
        void randomTxIdProduces32Bytes() {
            var txId = TestDataBuilder.randomTxId();
            assertInstanceOf(PlutusData.BytesData.class, txId);
            assertEquals(32, ((PlutusData.BytesData) txId).value().length);
        }

        @Test
        void randomTxOutRefProducesValidConstr() {
            var ref = TestDataBuilder.randomTxOutRef();
            assertInstanceOf(PlutusData.Constr.class, ref);
            var constr = (PlutusData.Constr) ref;
            assertEquals(0, constr.tag());
            assertEquals(2, constr.fields().size());
            // First field is Constr(0, [txId])
            assertInstanceOf(PlutusData.Constr.class, constr.fields().get(0));
            var txIdConstr = (PlutusData.Constr) constr.fields().get(0);
            assertEquals(0, txIdConstr.tag());
            assertInstanceOf(PlutusData.BytesData.class, txIdConstr.fields().get(0));
            // Second field is IntData (the index)
            assertInstanceOf(PlutusData.IntData.class, constr.fields().get(1));
        }

        @Test
        void intDataCreatesCorrectValue() {
            var data = TestDataBuilder.intData(42);
            assertInstanceOf(PlutusData.IntData.class, data);
            assertEquals(BigInteger.valueOf(42), ((PlutusData.IntData) data).value());
        }

        @Test
        void bytesDataCreatesCorrectValue() {
            var bytes = new byte[]{1, 2, 3};
            var data = TestDataBuilder.bytesData(bytes);
            assertInstanceOf(PlutusData.BytesData.class, data);
            assertArrayEquals(bytes, ((PlutusData.BytesData) data).value());
        }

        @Test
        void randomValuesAreUnique() {
            var pkh1 = TestDataBuilder.randomPubKeyHash();
            var pkh2 = TestDataBuilder.randomPubKeyHash();
            assertNotEquals(pkh1, pkh2, "Two random PubKeyHashes should differ");
        }
    }

    // -------------------------------------------------------------------------
    // Full lifecycle: compile source -> evaluate -> assert
    // -------------------------------------------------------------------------

    @Nested
    class FullLifecycleTests {

        /**
         * Build a minimal mock ScriptContext as PlutusData.
         * ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
         * The wrapper extracts redeemer as HeadList(TailList(SndPair(UnConstrData(ctx)))).
         */
        private PlutusData mockScriptContext(PlutusData redeemer) {
            var fakeTxInfo = PlutusData.integer(0);
            var fakeScriptInfo = PlutusData.integer(0);
            return PlutusData.constr(0, fakeTxInfo, redeemer, fakeScriptInfo);
        }

        @Test
        void compileAndEvaluateAlwaysTrue() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class AlwaysTrueValidator {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return true;
                        }
                    }
                    """;
            // The compiled validator expects a single ScriptContext argument
            var ctx = mockScriptContext(PlutusData.integer(0));
            var result = ValidatorTest.evaluate(source, ctx);
            BudgetAssertions.assertSuccess(result);
        }

        @Test
        void compileAndEvaluateAlwaysFalse() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class AlwaysFalseValidator {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return false;
                        }
                    }
                    """;
            var ctx = mockScriptContext(PlutusData.integer(0));
            var result = ValidatorTest.evaluate(source, ctx);
            BudgetAssertions.assertFailure(result);
        }
    }

    // -------------------------------------------------------------------------
    // ContractTest base class
    // -------------------------------------------------------------------------

    @Nested
    class ContractTestTests {

        /**
         * Concrete subclass for testing ContractTest.
         */
        static class TestableContract extends ContractTest {
            // expose protected methods for testing
        }

        @Test
        void vmCreatesSuccessfully() {
            var ct = new TestableContract();
            assertNotNull(ct.vm());
        }

        @Test
        void scriptContextBuildsValidConstr() {
            var ct = new TestableContract();
            var ctx = ct.scriptContext(PlutusData.integer(42));
            assertInstanceOf(PlutusData.Constr.class, ctx);
            var constr = (PlutusData.Constr) ctx;
            assertEquals(0, constr.tag());
            assertEquals(3, constr.fields().size());
            // field 1 is the redeemer
            assertEquals(PlutusData.integer(42), constr.fields().get(1));
        }

        @Test
        void evaluateAndAssertSuccess() {
            var ct = new TestableContract();
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class AlwaysTrue {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return true;
                        }
                    }
                    """;
            var ctx = ct.scriptContext(PlutusData.integer(0));
            var result = ct.evaluate(source, ctx);
            assertDoesNotThrow(() -> ct.assertSuccess(result));
        }

        @Test
        void evaluateAndAssertFailure() {
            var ct = new TestableContract();
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class AlwaysFalse {
                        @Entrypoint
                        static boolean validate(BigInteger redeemer, BigInteger ctx) {
                            return false;
                        }
                    }
                    """;
            var ctx = ct.scriptContext(PlutusData.integer(0));
            var result = ct.evaluate(source, ctx);
            assertDoesNotThrow(() -> ct.assertFailure(result));
        }
    }
}
