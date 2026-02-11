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
 * Regression tests that compile+evaluate validators calling @OnchainLibrary Java source methods.
 * <p>
 * These tests exercise the full pipeline: Java source → PIR → UPLC → VM evaluation,
 * covering bugs that were only caught by the external annotation-test project:
 * <ul>
 *   <li>Bug 1: Sequential var = mkCons(x, var) reassignment outside while loops</li>
 *   <li>Bug 2: FstPair(UnConstrData(...)) inferred as DataType instead of IntegerType</li>
 * </ul>
 */
class StdlibCompileEvalTest {

    static JulcVm vm;
    static final StdlibRegistry STDLIB = StdlibRegistry.defaultRegistry();

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    // ---- Test data helpers ----

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

    /** Build an "always" interval: (-inf, +inf) both inclusive. */
    static PlutusData alwaysInterval() {
        var negInf = PlutusData.constr(0);       // NegInf
        var posInf = PlutusData.constr(2);       // PosInf
        var trueVal = PlutusData.constr(1);      // Bool True
        var lowerBound = PlutusData.constr(0, negInf, trueVal);
        var upperBound = PlutusData.constr(0, posInf, trueVal);
        return PlutusData.constr(0, lowerBound, upperBound);
    }

    /** Build a finite inclusive interval [low, high]. */
    static PlutusData finiteInterval(long low, long high) {
        var trueVal = PlutusData.constr(1);      // Bool True
        var lowFinite = PlutusData.constr(1, PlutusData.integer(low));   // Finite(low)
        var highFinite = PlutusData.constr(1, PlutusData.integer(high)); // Finite(high)
        var lowerBound = PlutusData.constr(0, lowFinite, trueVal);
        var upperBound = PlutusData.constr(0, highFinite, trueVal);
        return PlutusData.constr(0, lowerBound, upperBound);
    }

    /** Build a simple Value with only lovelace: Map[(emptyBS, Map[(emptyBS, amount)])]. */
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

    /** Build a multi-asset Value: lovelace + one custom token. */
    static PlutusData multiAssetValue(long lovelace, byte[] policy, byte[] token, long amount) {
        return PlutusData.map(
                new PlutusData.Pair(
                        PlutusData.bytes(new byte[0]),
                        PlutusData.map(
                                new PlutusData.Pair(PlutusData.bytes(new byte[0]), PlutusData.integer(lovelace))
                        )
                ),
                new PlutusData.Pair(
                        PlutusData.bytes(policy),
                        PlutusData.map(
                                new PlutusData.Pair(PlutusData.bytes(token), PlutusData.integer(amount))
                        )
                )
        );
    }

    /** Build a simple map from integer keys to integer values. */
    static PlutusData simpleMap(long... keyValues) {
        var pairs = new PlutusData.Pair[keyValues.length / 2];
        for (int i = 0; i < keyValues.length; i += 2) {
            pairs[i / 2] = new PlutusData.Pair(PlutusData.integer(keyValues[i]), PlutusData.integer(keyValues[i + 1]));
        }
        return PlutusData.map(pairs);
    }

    /** Build a minimal ScriptContext: Constr(0, [txInfo, redeemer, scriptInfo]). */
    static PlutusData mockCtx(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),  // txInfo placeholder
                redeemer,
                PlutusData.integer(0)); // scriptInfo placeholder
    }

    /** Build a ScriptContext with a full TxInfo for interval/value testing. */
    static PlutusData fullCtx(PlutusData redeemer, PlutusData validRange) {
        var txInfo = buildTxInfo(new PlutusData[0], validRange);
        return PlutusData.constr(0, txInfo, redeemer, PlutusData.integer(0));
    }

    // ---- Compilation helpers ----

    static Program compileValidator(String source) {
        var compiler = new JulcCompiler(STDLIB::lookup);
        var result = compiler.compile(source);
        assertFalse(result.hasErrors(), "Compilation failed: " + result);
        assertNotNull(result.program(), "Program should not be null");
        return result.program();
    }

    // =========================================================================
    // 1. ConstrTagTypeInference — Bug 2 regression
    //    FstPair(UnConstrData(...)) must infer as IntegerType, not DataType
    // =========================================================================

    @Nested
    class ConstrTagTypeInference {

        @Test
        void constrTagEqualsAccepts() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var tag = Builtins.constrTag(redeemer);
                            return tag == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(0);  // tag = 0
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "constrTag(Constr(0)) == 0 should be true. Got: " + result);
        }

        @Test
        void constrTagEqualsRejects() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var tag = Builtins.constrTag(redeemer);
                            return tag == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(1);  // tag = 1
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertFalse(result.isSuccess(), "constrTag(Constr(1)) == 0 should be false. Got: " + result);
        }

        @Test
        void constrTagMultiBranch() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var tag = Builtins.constrTag(redeemer);
                            if (tag == 0) {
                                return false;
                            } else {
                                if (tag == 1) {
                                    return true;
                                } else {
                                    return false;
                                }
                            }
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(1);  // tag = 1 → should return true
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "constrTag multi-branch with tag=1 should succeed. Got: " + result);
        }

        @Test
        void constrTagInArithmetic() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var tag = Builtins.constrTag(redeemer);
                            return tag + 1 == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(1);  // tag = 1, so 1 + 1 == 2
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "constrTag(1) + 1 == 2 should be true. Got: " + result);
        }

        @Test
        void constrTagInlineComparison() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            if (Builtins.constrTag(redeemer) == 0) {
                                return true;
                            } else {
                                return false;
                            }
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(0);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "Inline constrTag(Constr(0)) == 0 should be true. Got: " + result);
        }
    }

    // =========================================================================
    // 2. IntervalLibEval — mkCons chains + constrTag dispatch in interval code
    // =========================================================================

    @Nested
    class IntervalLibEval {

        @Test
        void containsInAlwaysInterval() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData interval = IntervalLib.always();
                            return IntervalLib.contains(interval, 500);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "always() should contain 500. Got: " + result);
        }

        @Test
        void betweenThenContains() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData interval = IntervalLib.between(100, 200);
                            return IntervalLib.contains(interval, 150);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "between(100,200) should contain 150. Got: " + result);
        }

        @Test
        void isEmptyOnNever() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData interval = IntervalLib.never();
                            return IntervalLib.isEmpty(interval);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "never() should be empty. Got: " + result);
        }

        @Test
        void betweenDoesNotContainOutside() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData interval = IntervalLib.between(100, 200);
                            return IntervalLib.contains(interval, 250);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertFalse(result.isSuccess(), "between(100,200) should NOT contain 250. Got: " + result);
        }
    }

    // =========================================================================
    // 3. ValuesLibEval — Value operations + transitive ListsLib dependency
    // =========================================================================

    @Nested
    class ValuesLibEval {

        @Test
        void lovelaceOfExtractsAmount() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            long lovelace = ValuesLib.lovelaceOf(redeemer);
                            return lovelace == 5000000;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var value = simpleValue(5000000);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(value)));
            assertTrue(result.isSuccess(), "lovelaceOf should extract 5000000. Got: " + result);
        }

        @Test
        void singletonAndAssetOf() {
            // Test that singleton builds a Value and assetOf extracts the correct amount.
            // Policy and token are passed via the redeemer as a Constr(0, [BData(policy), BData(token)]).
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            PlutusData policy = Builtins.headList(fields);
                            PlutusData token = Builtins.headList(Builtins.tailList(fields));
                            PlutusData val = ValuesLib.singleton(policy, token, 42);
                            long amt = ValuesLib.assetOf(val, policy, token);
                            return amt == 42;
                        }
                    }
                    """;
            var program = compileValidator(source);
            // redeemer = Constr(0, [BData(policy), BData(token)])
            var redeemer = PlutusData.constr(0,
                    PlutusData.bytes(new byte[]{1, 2, 3}),
                    PlutusData.bytes(new byte[]{4, 5, 6}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "singleton then assetOf should return 42. Got: " + result);
        }

        @Test
        void geqComparesLargerVsSmaller() {
            // Test geq(larger, smaller) = true using singleton values with different amounts
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData policy = Builtins.bData(Builtins.emptyByteString());
                            PlutusData token = Builtins.bData(Builtins.emptyByteString());
                            PlutusData larger = ValuesLib.singleton(policy, token, 5000000);
                            PlutusData smaller = ValuesLib.singleton(policy, token, 3000000);
                            return ValuesLib.geq(larger, smaller);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "geq(5M, 3M) should be true. Got: " + result);
        }

        @Test
        void geqMultiAssetCompares() {
            // Build a Value inside the validator using singleton, then test geq(v, v) = true.
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData policy = Builtins.bData(Builtins.emptyByteString());
                            PlutusData token = Builtins.bData(Builtins.emptyByteString());
                            PlutusData val = ValuesLib.singleton(policy, token, 2000000);
                            return ValuesLib.geq(val, val);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "geq(v, v) should be true. Got: " + result);
        }
    }

    // =========================================================================
    // 4. MathLibEval — while loops, if-else branches in library code
    // =========================================================================

    @Nested
    class MathLibEval {

        @Test
        void divModReturnsConstr() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData result = MathLib.divMod(10, 3);
                            PlutusData fields = Builtins.constrFields(result);
                            long divVal = Builtins.unIData(Builtins.headList(fields));
                            if (divVal == 3) {
                                long modVal = Builtins.unIData(Builtins.headList(Builtins.tailList(fields)));
                                return modVal == 1;
                            } else {
                                return false;
                            }
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "divMod(10,3) should be (3,1). Got: " + result);
        }

        @Test
        void powWithWhileLoop() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            long result = MathLib.pow(2, 10);
                            return result == 1024;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "pow(2,10) should be 1024. Got: " + result);
        }

        @Test
        void absAndSign() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            long absVal = MathLib.abs(0 - 5);
                            long signVal = MathLib.sign(0 - 5);
                            return absVal == 5 && signVal + 1 == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "abs(-5)==5 && sign(-5)+1==0 should be true. Got: " + result);
        }
    }

    // =========================================================================
    // 5. MapLibEval — while loops with break, constrTag on result
    // =========================================================================

    @Nested
    class MapLibEval {

        @Test
        void lookupFound() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData result = MapLib.lookup(redeemer, Builtins.iData(1));
                            var tag = Builtins.constrTag(result);
                            return tag == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            // map: {1 -> 100, 2 -> 200}
            var map = simpleMap(1, 100, 2, 200);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(map)));
            assertTrue(result.isSuccess(), "lookup({1:100,2:200}, 1) should return Some (tag=0). Got: " + result);
        }

        @Test
        void memberTrue() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return MapLib.member(redeemer, Builtins.iData(2));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var map = simpleMap(1, 100, 2, 200);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(map)));
            assertTrue(result.isSuccess(), "member({1:100,2:200}, 2) should be true. Got: " + result);
        }

        @Test
        void keysReturnsNonEmpty() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData keysList = MapLib.keys(redeemer);
                            return !Builtins.nullList(keysList);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var map = simpleMap(1, 100, 2, 200);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(map)));
            assertTrue(result.isSuccess(), "keys({1:100,2:200}) should be non-empty. Got: " + result);
        }
    }

    // =========================================================================
    // 6. TransitiveDependencies — user @OnchainLibrary calling stdlib methods
    // =========================================================================

    @Nested
    class TransitiveDependencies {

        @Test
        void userLibCallsBuiltins() {
            // User library that calls Builtins methods (always available via StdlibRegistry)
            var userLib = """
                    package com.example;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;

                    @OnchainLibrary
                    public class MyDataLib {
                        public static long extractTag(PlutusData data) {
                            return Builtins.constrTag(data);
                        }
                    }
                    """;
            var validator = """
                    import com.example.MyDataLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            long tag = MyDataLib.extractTag(redeemer);
                            return tag == 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler(STDLIB::lookup);
            var result = compiler.compile(validator, List.of(userLib));
            assertFalse(result.hasErrors(), "Compilation should succeed: " + result);
            var evalResult = vm.evaluateWithArgs(result.program(), List.of(mockCtx(PlutusData.constr(0))));
            assertTrue(evalResult.isSuccess(), "User lib calling Builtins.constrTag() should work. Got: " + evalResult);
        }

        @Test
        void userLibCallsMathDelegates() {
            // User library that calls Math.abs (registered as Java math delegate in StdlibRegistry)
            var userLib = """
                    package com.example;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;

                    @OnchainLibrary
                    public class MyMathLib {
                        public static long absVal(long x) {
                            return Math.abs(x);
                        }
                    }
                    """;
            var validator = """
                    import com.example.MyMathLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            long result = MyMathLib.absVal(0 - 7);
                            return result == 7;
                        }
                    }
                    """;
            var compiler = new JulcCompiler(STDLIB::lookup);
            var result = compiler.compile(validator, List.of(userLib));
            assertFalse(result.hasErrors(), "Compilation should succeed: " + result);
            var evalResult = vm.evaluateWithArgs(result.program(), List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(evalResult.isSuccess(), "User lib calling Math.abs() should work. Got: " + evalResult);
        }

        @Test
        void userLibCallsUserLib() {
            var helperLib = """
                    package com.example;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;

                    @OnchainLibrary
                    public class MathHelper {
                        public static long doubleIt(long x) {
                            return x + x;
                        }
                    }
                    """;
            var middleLib = """
                    package com.example;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;

                    @OnchainLibrary
                    public class MiddleLib {
                        public static long quadruple(long x) {
                            return MathHelper.doubleIt(MathHelper.doubleIt(x));
                        }
                    }
                    """;
            var validator = """
                    import com.example.MiddleLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            long result = MiddleLib.quadruple(3);
                            return result == 12;
                        }
                    }
                    """;
            var compiler = new JulcCompiler(STDLIB::lookup);
            var result = compiler.compile(validator, List.of(helperLib, middleLib));
            assertFalse(result.hasErrors(), "Compilation should succeed: " + result);
            var evalResult = vm.evaluateWithArgs(result.program(), List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(evalResult.isSuccess(), "3-level transitive user lib chain should work. Got: " + evalResult);
        }
    }
}
