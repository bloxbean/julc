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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

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
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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

        @Test
        void addTwoValues() {
            // Test ValuesLib.add with two singleton values for different policies.
            // This exercises adjustOuterForAdd + extraOuterEntries which have mkNilPairData+mkCons patterns.
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData policyA = Builtins.bData(Builtins.emptyByteString());
                            PlutusData tokenA = Builtins.bData(Builtins.emptyByteString());
                            PlutusData.MapData valA = ValuesLib.singleton(policyA, tokenA, 2000000);
                            var fields = Builtins.constrFields(redeemer);
                            PlutusData policyB = Builtins.headList(fields);
                            PlutusData tokenB = Builtins.headList(Builtins.tailList(fields));
                            PlutusData.MapData valB = ValuesLib.singleton(policyB, tokenB, 1000000);
                            PlutusData.MapData sum = ValuesLib.add(valA, valB);
                            long amtA = ValuesLib.assetOf(sum, policyA, tokenA);
                            long amtB = ValuesLib.assetOf(sum, policyB, tokenB);
                            return amtA == 2000000 && amtB == 1000000;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(0,
                    PlutusData.bytes(new byte[]{1, 2, 3}),
                    PlutusData.bytes(new byte[]{4, 5, 6}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "add(valA, valB) should work. Got: " + result);
        }

        /**
         * Regression: while loop over outputs (list data) with boolean+list multi-acc.
         * Inner body uses fstPair/sndPair on map entries but the cursor should remain ListType.
         * Simulates IssuanceMint.findMintOutput pattern.
         */
        @Test
        void findMintOutputPattern() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            // Build 2 outputs as list of Constrs, each containing a Value
                            PlutusData.MapData val1 = ValuesLib.singleton(
                                Builtins.bData(Builtins.emptyByteString()),
                                Builtins.bData(Builtins.emptyByteString()), 2000000);
                            PlutusData.MapData val2 = ValuesLib.singleton(
                                Builtins.bData(Builtins.emptyByteString()),
                                Builtins.bData(Builtins.emptyByteString()), 1000000);
                            // Outputs as list of Constr(0, [addr, value])
                            PlutusData out1 = Builtins.constrData(0, Builtins.mkCons(Builtins.iData(1),
                                Builtins.mkCons(val1, Builtins.mkNilData())));
                            PlutusData out2 = Builtins.constrData(0, Builtins.mkCons(Builtins.iData(2),
                                Builtins.mkCons(val2, Builtins.mkNilData())));
                            PlutusData.ListData outputs = Builtins.mkCons(out1, Builtins.mkCons(out2, Builtins.mkNilData()));

                            // Multi-acc while loop: boolean found + list remaining
                            boolean found = false;
                            PlutusData remaining = outputs;
                            while (!Builtins.nullList(remaining)) {
                                PlutusData output = Builtins.headList(remaining);
                                PlutusData fields = Builtins.constrFields(output);
                                PlutusData valueData = Builtins.headList(Builtins.tailList(fields));
                                // Extract from inner map: uses unMapData, fstPair, sndPair
                                PlutusData outerPairs = Builtins.unMapData(valueData);
                                if (!Builtins.nullList(outerPairs)) {
                                    PlutusData pair = Builtins.headList(outerPairs);
                                    PlutusData tokenMap = Builtins.sndPair(pair);
                                    PlutusData innerPairs = Builtins.unMapData(tokenMap);
                                    PlutusData firstTokenPair = Builtins.headList(innerPairs);
                                    long amt = Builtins.unIData(Builtins.sndPair(firstTokenPair));
                                    if (amt == 2000000) {
                                        found = true;
                                    }
                                }
                                remaining = Builtins.tailList(remaining);
                            }
                            return found;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "findMintOutput pattern should work. Got: " + result);
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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

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
    // 6. AddressLibEval — Address utility operations
    // =========================================================================

    @Nested
    class AddressLibEval {

        @Test
        void credentialHashExtractsFromScriptAddress() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] hash = AddressLib.credentialHash(redeemer);
                            return Builtins.equalsByteString(hash, Builtins.unBData(Builtins.headList(
                                Builtins.constrFields(Builtins.headList(Builtins.constrFields(redeemer))))));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var scriptCred = PlutusData.constr(1, PlutusData.bytes(new byte[]{10, 20, 30}));
            var address = PlutusData.constr(0, scriptCred, PlutusData.constr(1));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(address)));
            assertTrue(result.isSuccess(), "credentialHash on ScriptCredential should extract hash. Got: " + result);
        }

        @Test
        void credentialHashExtractsFromPubKeyAddress() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] hash = AddressLib.credentialHash(redeemer);
                            return Builtins.equalsByteString(hash, Builtins.unBData(Builtins.headList(
                                Builtins.constrFields(Builtins.headList(Builtins.constrFields(redeemer))))));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var pubKeyCred = PlutusData.constr(0, PlutusData.bytes(new byte[]{1, 2, 3, 4}));
            var address = PlutusData.constr(0, pubKeyCred, PlutusData.constr(1));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(address)));
            assertTrue(result.isSuccess(), "credentialHash on PubKeyCredential should extract hash. Got: " + result);
        }

        @Test
        void isScriptAddressReturnsTrue() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return AddressLib.isScriptAddress(redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var scriptCred = PlutusData.constr(1, PlutusData.bytes(new byte[]{1, 2, 3}));
            var address = PlutusData.constr(0, scriptCred, PlutusData.constr(1));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(address)));
            assertTrue(result.isSuccess(), "isScriptAddress on ScriptCredential should be true. Got: " + result);
        }

        @Test
        void isScriptAddressReturnsFalseForPubKey() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return AddressLib.isScriptAddress(redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var pubKeyCred = PlutusData.constr(0, PlutusData.bytes(new byte[]{1, 2, 3}));
            var address = PlutusData.constr(0, pubKeyCred, PlutusData.constr(1));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(address)));
            assertFalse(result.isSuccess(), "isScriptAddress on PubKeyCredential should be false. Got: " + result);
        }

        @Test
        void isPubKeyAddressReturnsTrue() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return AddressLib.isPubKeyAddress(redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var pubKeyCred = PlutusData.constr(0, PlutusData.bytes(new byte[]{1, 2, 3}));
            var address = PlutusData.constr(0, pubKeyCred, PlutusData.constr(1));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(address)));
            assertTrue(result.isSuccess(), "isPubKeyAddress on PubKeyCredential should be true. Got: " + result);
        }

        @Test
        void paymentCredentialExtractsCredential() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData cred = AddressLib.paymentCredential(redeemer);
                            return Builtins.constrTag(cred) == 1;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var scriptCred = PlutusData.constr(1, PlutusData.bytes(new byte[]{10, 20}));
            var address = PlutusData.constr(0, scriptCred, PlutusData.constr(1));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(address)));
            assertTrue(result.isSuccess(), "paymentCredential should extract ScriptCredential (tag=1). Got: " + result);
        }
    }

    // =========================================================================
    // 7. IntervalLibBoundEval — finiteUpperBound / finiteLowerBound
    // =========================================================================

    @Nested
    class IntervalLibBoundEval {

        @Test
        void finiteUpperBoundExtractsTime() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData interval = IntervalLib.between(100, 500);
                            return IntervalLib.finiteUpperBound(interval) == 500;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "finiteUpperBound(between(100,500)) should be 500. Got: " + result);
        }

        @Test
        void finiteLowerBoundExtractsTime() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData interval = IntervalLib.between(100, 500);
                            return IntervalLib.finiteLowerBound(interval) == 100;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "finiteLowerBound(between(100,500)) should be 100. Got: " + result);
        }

        @Test
        void finiteUpperBoundOnAlwaysReturnsMinus1() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData interval = IntervalLib.always();
                            return IntervalLib.finiteUpperBound(interval) + 1 == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "finiteUpperBound(always()) should be -1. Got: " + result);
        }

        @Test
        void finiteLowerBoundOnAlwaysReturnsMinus1() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData interval = IntervalLib.always();
                            return IntervalLib.finiteLowerBound(interval) + 1 == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "finiteLowerBound(always()) should be -1. Got: " + result);
        }

        @Test
        void finiteUpperBoundOnAfterReturnsMinus1() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData interval = IntervalLib.after(1000);
                            return IntervalLib.finiteUpperBound(interval) + 1 == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "finiteUpperBound(after(1000)) should be -1 (PosInf). Got: " + result);
        }

        @Test
        void finiteLowerBoundOnAfterExtractsTime() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData interval = IntervalLib.after(1000);
                            return IntervalLib.finiteLowerBound(interval) == 1000;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "finiteLowerBound(after(1000)) should be 1000. Got: " + result);
        }
    }

    // =========================================================================
    // 8. ListsLibExtendedEval — hasDuplicateInts, hasDuplicateBytes, containsBytes
    // =========================================================================

    @Nested
    class ListsLibExtendedEval {

        @Test
        void hasDuplicateIntsFindsExactDuplicate() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return ListsLib.hasDuplicateInts(Builtins.unListData(redeemer));
                        }
                    }
                    """;
            var program = compileValidator(source);
            // list: [1, 2, 3, 2]  — has duplicate 2
            var list = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2),
                    PlutusData.integer(3), PlutusData.integer(2));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(list)));
            assertTrue(result.isSuccess(), "hasDuplicateInts([1,2,3,2]) should be true. Got: " + result);
        }

        @Test
        void hasDuplicateIntsReturnsFalseForNoDups() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return ListsLib.hasDuplicateInts(Builtins.unListData(redeemer));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var list = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(list)));
            assertFalse(result.isSuccess(), "hasDuplicateInts([1,2,3]) should be false. Got: " + result);
        }

        @Test
        void hasDuplicateIntsOnEmptyList() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return ListsLib.hasDuplicateInts(Builtins.unListData(redeemer));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var list = PlutusData.list();
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(list)));
            assertFalse(result.isSuccess(), "hasDuplicateInts([]) should be false. Got: " + result);
        }

        @Test
        void hasDuplicateBytesFindsExactDuplicate() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return ListsLib.hasDuplicateBytes(Builtins.unListData(redeemer));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var list = PlutusData.list(PlutusData.bytes(new byte[]{1, 2}), PlutusData.bytes(new byte[]{3, 4}),
                    PlutusData.bytes(new byte[]{1, 2}));  // duplicate
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(list)));
            assertTrue(result.isSuccess(), "hasDuplicateBytes with duplicate should be true. Got: " + result);
        }

        @Test
        void hasDuplicateBytesReturnsFalseForNoDups() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return ListsLib.hasDuplicateBytes(Builtins.unListData(redeemer));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var list = PlutusData.list(PlutusData.bytes(new byte[]{1}), PlutusData.bytes(new byte[]{2}),
                    PlutusData.bytes(new byte[]{3}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(list)));
            assertFalse(result.isSuccess(), "hasDuplicateBytes without duplicates should be false. Got: " + result);
        }

        @Test
        void containsBytesFindsTarget() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData fields = Builtins.constrFields(redeemer);
                            PlutusData listData = Builtins.headList(fields);
                            byte[] target = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            return ListsLib.containsBytes(Builtins.unListData(listData), target);
                        }
                    }
                    """;
            var program = compileValidator(source);
            // redeemer = Constr(0, [ListData([B"abc", B"def"]), BData("def")])
            var list = PlutusData.list(PlutusData.bytes(new byte[]{1, 2, 3}), PlutusData.bytes(new byte[]{4, 5, 6}));
            var target = PlutusData.bytes(new byte[]{4, 5, 6});
            var redeemer = PlutusData.constr(0, list, target);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "containsBytes should find target. Got: " + result);
        }

        @Test
        void containsBytesReturnsFalseWhenNotFound() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData fields = Builtins.constrFields(redeemer);
                            PlutusData listData = Builtins.headList(fields);
                            byte[] target = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            return ListsLib.containsBytes(Builtins.unListData(listData), target);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var list = PlutusData.list(PlutusData.bytes(new byte[]{1, 2, 3}), PlutusData.bytes(new byte[]{4, 5, 6}));
            var target = PlutusData.bytes(new byte[]{7, 8, 9});
            var redeemer = PlutusData.constr(0, list, target);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertFalse(result.isSuccess(), "containsBytes should not find missing target. Got: " + result);
        }
    }

    // =========================================================================
    // 9. ContextsLibExtendedEval — txInfoRefInputs, txInfoWithdrawals, txInfoRedeemers
    // =========================================================================

    @Nested
    class ContextsLibExtendedEval {

        /** Build a full TxInfo with all 16 fields populated for field extraction testing. */
        static PlutusData buildFullTxInfo() {
            return PlutusData.constr(0,
                    PlutusData.list(PlutusData.integer(1)),               // 0: inputs
                    PlutusData.list(PlutusData.integer(2)),               // 1: referenceInputs
                    PlutusData.list(PlutusData.integer(3)),               // 2: outputs
                    PlutusData.integer(2000000),                           // 3: fee
                    PlutusData.map(),                                      // 4: mint
                    PlutusData.list(),                                     // 5: certificates
                    PlutusData.map(new PlutusData.Pair(                   // 6: withdrawals
                            PlutusData.constr(0, PlutusData.bytes(new byte[]{7})),
                            PlutusData.integer(0))),
                    alwaysInterval(),                                      // 7: validRange
                    PlutusData.list(PlutusData.bytes(new byte[]{8})),     // 8: signatories
                    PlutusData.map(new PlutusData.Pair(                   // 9: redeemers
                            PlutusData.constr(0, PlutusData.bytes(new byte[]{9})),
                            PlutusData.integer(42))),
                    PlutusData.map(),                                      // 10: datums
                    PlutusData.bytes(new byte[32]),                        // 11: txId
                    PlutusData.map(),                                      // 12: votes
                    PlutusData.list(),                                     // 13: proposalProcedures
                    PlutusData.constr(1),                                  // 14: currentTreasuryAmount (None)
                    PlutusData.constr(1)                                   // 15: treasuryDonation (None)
            );
        }

        @Test
        void txInfoRefInputsExtractsField1() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                            PlutusData refInputs = ContextsLib.txInfoRefInputs(txInfo);
                            // refInputs should be a list; check it's non-empty
                            return !Builtins.nullList(refInputs);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var txInfo = buildFullTxInfo();
            var ctx = PlutusData.constr(0, txInfo, PlutusData.integer(0), PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "txInfoRefInputs should extract non-empty list. Got: " + result);
        }

        @Test
        void txInfoWithdrawalsExtractsField6() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                            PlutusData withdrawals = ContextsLib.txInfoWithdrawals(txInfo);
                            // withdrawals returns JulcMap (already unwrapped pair list); check non-empty
                            return !Builtins.nullList(withdrawals);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var txInfo = buildFullTxInfo();
            var ctx = PlutusData.constr(0, txInfo, PlutusData.integer(0), PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "txInfoWithdrawals should extract non-empty map. Got: " + result);
        }

        @Test
        void txInfoRedeemersExtractsField9() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                            PlutusData redeemers = ContextsLib.txInfoRedeemers(txInfo);
                            // redeemers returns JulcMap (already unwrapped pair list); check non-empty
                            return !Builtins.nullList(redeemers);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var txInfo = buildFullTxInfo();
            var ctx = PlutusData.constr(0, txInfo, PlutusData.integer(0), PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "txInfoRedeemers should extract non-empty map. Got: " + result);
        }
    }

    // =========================================================================
    // 10. OutputLibEval — Output utility library (ledger types)
    // =========================================================================

    @Nested
    class OutputLibEval {

        /**
         * Build Address: Constr(0, [PubKeyCredential(pkh), noStaking]).
         * PubKeyCredential = Constr(0, [pkh_bytes])
         * noStaking = Constr(1, []) (None)
         */
        static PlutusData buildAddress(byte[] pkh) {
            var pubKeyCred = PlutusData.constr(0, PlutusData.bytes(pkh));
            var noStaking = PlutusData.constr(1);
            return PlutusData.constr(0, pubKeyCred, noStaking);
        }

        /**
         * Build a TxOut: Constr(0, [address, value, datum, refScript]).
         */
        static PlutusData buildTxOut(PlutusData address, PlutusData value, PlutusData datum) {
            var noRefScript = PlutusData.constr(1);    // None
            return PlutusData.constr(0, address, value, datum, noRefScript);
        }

        /** Build TxOut with NoOutputDatum. */
        static PlutusData buildTxOut(PlutusData address, PlutusData value) {
            return buildTxOut(address, value, PlutusData.constr(0)); // NoOutputDatum
        }

        /** Build a Value with only lovelace. */
        static PlutusData lovelaceValue(long lovelace) {
            return simpleValue(lovelace);
        }

        /** Build a Value with lovelace + one token. */
        static PlutusData tokenValue(long lovelace, byte[] policy, byte[] token, long amount) {
            return multiAssetValue(lovelace, policy, token, amount);
        }

        @Test
        void txOutAddressExtractsCorrectField() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            // redeemer = Constr(0, [txOut, expectedAddress])
                            PlutusData fields = Builtins.constrFields(redeemer);
                            TxOut txOut = Builtins.headList(fields);
                            Address expected = Builtins.headList(Builtins.tailList(fields));
                            Address actual = OutputLib.txOutAddress(txOut);
                            return actual == expected;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var pkh = new byte[]{1, 2, 3, 4, 5};
            var address = buildAddress(pkh);
            var txOut = buildTxOut(address, lovelaceValue(2000000));
            var redeemer = PlutusData.constr(0, txOut, address);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "txOutAddress should extract address. Got: " + result);
        }

        @Test
        void txOutValueExtractsLovelace() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            // redeemer = a TxOut
                            Value val = OutputLib.txOutValue(redeemer);
                            // Extract lovelace from Value using Builtins
                            var pairs = Builtins.unMapData(val);
                            var firstPair = Builtins.headList(pairs);
                            var tokenMap = Builtins.sndPair(firstPair);
                            var tokenPairs = Builtins.unMapData(tokenMap);
                            var firstToken = Builtins.headList(tokenPairs);
                            long lovelace = Builtins.unIData(Builtins.sndPair(firstToken));
                            return lovelace == 3000000;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var txOut = buildTxOut(buildAddress(new byte[]{1}), lovelaceValue(3000000));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(txOut)));
            assertTrue(result.isSuccess(), "txOutValue should extract value with 3M lovelace. Got: " + result);
        }

        @Test
        void txOutDatumExtractsTag() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            // redeemer = TxOut with InlineDatum
                            OutputDatum d = OutputLib.txOutDatum(redeemer);
                            // InlineDatum is Constr(2, [datum]) — check tag == 2
                            return Builtins.constrTag(d) == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var inlineDatum = PlutusData.constr(2, PlutusData.integer(42)); // OutputDatumInline(42)
            var txOut = buildTxOut(buildAddress(new byte[]{1}), lovelaceValue(2000000), inlineDatum);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(txOut)));
            assertTrue(result.isSuccess(), "txOutDatum should extract InlineDatum tag=2. Got: " + result);
        }

        @Test
        void outputsAtFiltersMatchingAddress() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            // redeemer = Constr(0, [outputs_list, target_address])
                            PlutusData fields = Builtins.constrFields(redeemer);
                            List<TxOut> outputs = Builtins.unListData(Builtins.headList(fields));
                            Address addr = Builtins.headList(Builtins.tailList(fields));
                            List<TxOut> matched = OutputLib.outputsAt(outputs, addr);
                            return ListsLib.length(matched) == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var addr1 = buildAddress(new byte[]{1, 2, 3});
            var addr2 = buildAddress(new byte[]{4, 5, 6});
            var out1 = buildTxOut(addr1, lovelaceValue(1000000));
            var out2 = buildTxOut(addr1, lovelaceValue(2000000));
            var out3 = buildTxOut(addr2, lovelaceValue(3000000));
            var outputs = PlutusData.list(out1, out2, out3);
            var redeemer = PlutusData.constr(0, outputs, addr1);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "outputsAt should return 2 matching outputs. Got: " + result);
        }

        @Test
        void countOutputsAtReturnsCorrect() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData fields = Builtins.constrFields(redeemer);
                            List<TxOut> outputs = Builtins.unListData(Builtins.headList(fields));
                            Address addr = Builtins.headList(Builtins.tailList(fields));
                            return OutputLib.countOutputsAt(outputs, addr) == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var addr = buildAddress(new byte[]{1, 2, 3});
            var out1 = buildTxOut(addr, lovelaceValue(1000000));
            var out2 = buildTxOut(addr, lovelaceValue(2000000));
            var out3 = buildTxOut(addr, lovelaceValue(3000000));
            var outputs = PlutusData.list(out1, out2, out3);
            var redeemer = PlutusData.constr(0, outputs, addr);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "countOutputsAt should return 3. Got: " + result);
        }

        @Test
        void uniqueOutputAtSucceeds() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData fields = Builtins.constrFields(redeemer);
                            List<TxOut> outputs = Builtins.unListData(Builtins.headList(fields));
                            Address addr = Builtins.headList(Builtins.tailList(fields));
                            TxOut unique = OutputLib.uniqueOutputAt(outputs, addr);
                            // Verify it's the expected output by checking address equality
                            return OutputLib.txOutAddress(unique) == addr;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var addr1 = buildAddress(new byte[]{1, 2, 3});
            var addr2 = buildAddress(new byte[]{4, 5, 6});
            var out1 = buildTxOut(addr1, lovelaceValue(1000000));
            var out2 = buildTxOut(addr2, lovelaceValue(2000000));
            var outputs = PlutusData.list(out1, out2);
            var redeemer = PlutusData.constr(0, outputs, addr1);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "uniqueOutputAt should succeed with 1 match. Got: " + result);
        }

        @Test
        void uniqueOutputAtErrorsNoMatch() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData fields = Builtins.constrFields(redeemer);
                            List<TxOut> outputs = Builtins.unListData(Builtins.headList(fields));
                            Address addr = Builtins.headList(Builtins.tailList(fields));
                            TxOut unique = OutputLib.uniqueOutputAt(outputs, addr);
                            // Force evaluation of unique by comparing its address field
                            return unique.address() == addr;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var addr1 = buildAddress(new byte[]{1, 2, 3});
            var addr2 = buildAddress(new byte[]{4, 5, 6});
            var out1 = buildTxOut(addr2, lovelaceValue(1000000));
            var outputs = PlutusData.list(out1);
            var redeemer = PlutusData.constr(0, outputs, addr1);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertFalse(result.isSuccess(), "uniqueOutputAt should abort with 0 matches. Got: " + result);
        }

        @Test
        void outputsWithTokenFindsMatch() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData fields = Builtins.constrFields(redeemer);
                            List<TxOut> outputs = Builtins.unListData(Builtins.headList(fields));
                            PlutusData policyId = Builtins.headList(Builtins.tailList(fields));
                            PlutusData tokenName = Builtins.headList(Builtins.tailList(Builtins.tailList(fields)));
                            List<TxOut> matched = OutputLib.outputsWithToken(outputs, policyId, tokenName);
                            return ListsLib.length(matched) == 1;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var addr = buildAddress(new byte[]{1, 2, 3});
            var policy = new byte[]{10, 20, 30};
            var token = new byte[]{40, 50, 60};
            var out1 = buildTxOut(addr, tokenValue(2000000, policy, token, 100));
            var out2 = buildTxOut(addr, lovelaceValue(3000000)); // no token
            var outputs = PlutusData.list(out1, out2);
            var redeemer = PlutusData.constr(0, outputs, PlutusData.bytes(policy), PlutusData.bytes(token));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "outputsWithToken should find 1 match. Got: " + result);
        }

        @Test
        void outputsWithTokenEmptyNoMatch() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData fields = Builtins.constrFields(redeemer);
                            List<TxOut> outputs = Builtins.unListData(Builtins.headList(fields));
                            PlutusData policyId = Builtins.headList(Builtins.tailList(fields));
                            PlutusData tokenName = Builtins.headList(Builtins.tailList(Builtins.tailList(fields)));
                            List<TxOut> matched = OutputLib.outputsWithToken(outputs, policyId, tokenName);
                            return ListsLib.length(matched) == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var addr = buildAddress(new byte[]{1, 2, 3});
            var policy = new byte[]{10, 20, 30};
            var token = new byte[]{40, 50, 60};
            var out1 = buildTxOut(addr, lovelaceValue(2000000)); // no token
            var out2 = buildTxOut(addr, lovelaceValue(3000000)); // no token
            var outputs = PlutusData.list(out1, out2);
            var redeemer = PlutusData.constr(0, outputs, PlutusData.bytes(policy), PlutusData.bytes(token));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "outputsWithToken should find 0 matches. Got: " + result);
        }

        @Test
        void lovelacePaidToSumsCorrectly() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData fields = Builtins.constrFields(redeemer);
                            List<TxOut> outputs = Builtins.unListData(Builtins.headList(fields));
                            Address addr = Builtins.headList(Builtins.tailList(fields));
                            return OutputLib.lovelacePaidTo(outputs, addr).longValue() == 10000000;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var addr1 = buildAddress(new byte[]{1, 2, 3});
            var addr2 = buildAddress(new byte[]{4, 5, 6});
            var out1 = buildTxOut(addr1, lovelaceValue(2000000));
            var out2 = buildTxOut(addr1, lovelaceValue(3000000));
            var out3 = buildTxOut(addr2, lovelaceValue(4000000));
            var out4 = buildTxOut(addr1, lovelaceValue(5000000));
            var outputs = PlutusData.list(out1, out2, out3, out4);
            var redeemer = PlutusData.constr(0, outputs, addr1);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "lovelacePaidTo should sum 2M+3M+5M=10M. Got: " + result);
        }

        @Test
        void paidAtLeastSucceeds() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData fields = Builtins.constrFields(redeemer);
                            List<TxOut> outputs = Builtins.unListData(Builtins.headList(fields));
                            Address addr = Builtins.headList(Builtins.tailList(fields));
                            return OutputLib.paidAtLeast(outputs, addr, BigInteger.valueOf(4000000));
                        }
                    }
                    """;
            var program = compileValidator(source);

            var addr = buildAddress(new byte[]{1, 2, 3});
            var out1 = buildTxOut(addr, lovelaceValue(2000000));
            var out2 = buildTxOut(addr, lovelaceValue(3000000));
            var outputs = PlutusData.list(out1, out2);
            var redeemer = PlutusData.constr(0, outputs, addr);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "paidAtLeast(5M >= 4M) should be true. Got: " + result);
        }

        @Test
        void getInlineDatumExtractsValue() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            // redeemer = TxOut with InlineDatum(IData(42))
                            PlutusData datum = OutputLib.getInlineDatum(redeemer);
                            return Builtins.unIData(datum) == 42;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var inlineDatum = PlutusData.constr(2, PlutusData.integer(42)); // OutputDatumInline(42)
            var txOut = buildTxOut(buildAddress(new byte[]{1}), lovelaceValue(2000000), inlineDatum);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(txOut)));
            assertTrue(result.isSuccess(), "getInlineDatum should extract 42. Got: " + result);
        }

        @Test
        void resolveDatumLooksUpHash() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            // redeemer = Constr(0, [txOut, datumsMap])
                            PlutusData fields = Builtins.constrFields(redeemer);
                            TxOut txOut = Builtins.headList(fields);
                            PlutusData.MapData datumsMap = Builtins.headList(Builtins.tailList(fields));
                            PlutusData datum = OutputLib.resolveDatum(txOut, datumsMap);
                            return Builtins.unIData(datum) == 99;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var hashBytes = new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC};
            var datumHash = PlutusData.constr(1, PlutusData.bytes(hashBytes)); // OutputDatumHash
            var txOut = buildTxOut(buildAddress(new byte[]{1}), lovelaceValue(2000000), datumHash);
            // datumsMap: Map[(BData(hashBytes), IData(99))]
            var datumsMap = PlutusData.map(
                    new PlutusData.Pair(PlutusData.bytes(hashBytes), PlutusData.integer(99)));
            var redeemer = PlutusData.constr(0, txOut, datumsMap);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "resolveDatum should look up hash and return 99. Got: " + result);
        }
    }

    // =========================================================================
    // 11. TransitiveDependencies — user @OnchainLibrary calling stdlib methods
    // =========================================================================

    @Nested
    class TransitiveDependencies {

        @Test
        void userLibCallsBuiltins() {
            // User library that calls Builtins methods (always available via StdlibRegistry)
            var userLib = """
                    package com.example;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

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
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

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
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

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
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

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

    // =========================================================================
    // High-Level Abstractions — pair.key/value, value.lovelaceOf/assetOf/isEmpty,
    // map.isEmpty/keys/values, for-each over map
    // =========================================================================

    @Nested
    class HighLevelAbstractions {

        // --- Pair instance methods ---

        @Test
        void pairKeyOnMapEntry() {
            var source = """
                    import java.util.Map;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            long total = 0;
                            for (var entry : m) {
                                total = total + Builtins.unIData(entry.key());
                            }
                            return total == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            // Map with keys 1 and 2 → total = 3
            var mapData = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20))
            );
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(mapData)));
            assertTrue(result.isSuccess(), "pair.key() in for-each over map should work. Got: " + result);
        }

        @Test
        void pairValueOnMapEntry() {
            var source = """
                    import java.util.Map;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            long total = 0;
                            for (var entry : m) {
                                total = total + Builtins.unIData(entry.value());
                            }
                            return total == 30;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var mapData = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20))
            );
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(mapData)));
            assertTrue(result.isSuccess(), "pair.value() in for-each over map should work. Got: " + result);
        }

        // --- Map instance methods ---

        @Test
        void mapIsEmptyTrue() {
            var source = """
                    import java.util.Map;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            return m.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.map())));
            assertTrue(result.isSuccess(), "Empty map.isEmpty() should return true. Got: " + result);
        }

        @Test
        void mapIsEmptyFalse() {
            var source = """
                    import java.util.Map;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            return !m.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var mapData = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10))
            );
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(mapData)));
            assertTrue(result.isSuccess(), "Non-empty map.isEmpty() should return false. Got: " + result);
        }

        @Test
        void mapKeysCollectsKeys() {
            var source = """
                    import java.util.Map;
                    import java.util.List;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            List<PlutusData> ks = (List<PlutusData>)(Object) m.keys();
                            return ks.size() == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var mapData = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20))
            );
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(mapData)));
            assertTrue(result.isSuccess(), "map.keys().size() should be 2. Got: " + result);
        }

        @Test
        void mapValuesCollectsValues() {
            var source = """
                    import java.util.Map;
                    import java.util.List;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            List<PlutusData> vs = (List<PlutusData>)(Object) m.values();
                            return vs.size() == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var mapData = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)),
                    new PlutusData.Pair(PlutusData.integer(3), PlutusData.integer(30))
            );
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(mapData)));
            assertTrue(result.isSuccess(), "map.values().size() should be 3. Got: " + result);
        }

        // --- For-each over Map ---

        @Test
        void forEachOverMapAccumulates() {
            var source = """
                    import java.util.Map;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            long sum = 0;
                            for (var entry : m) {
                                sum = sum + Builtins.unIData(entry.key()) + Builtins.unIData(entry.value());
                            }
                            return sum == 33;
                        }
                    }
                    """;
            var program = compileValidator(source);
            // keys: 1,2  values: 10,20 → sum = 1+10+2+20 = 33
            var mapData = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20))
            );
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(mapData)));
            assertTrue(result.isSuccess(), "for-each over map with accumulator should work. Got: " + result);
        }

        @Test
        void forEachOverEmptyMap() {
            var source = """
                    import java.util.Map;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            long count = 0;
                            for (var entry : m) {
                                count = count + 1;
                            }
                            return count == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.map())));
            assertTrue(result.isSuccess(), "for-each over empty map should yield 0. Got: " + result);
        }

        // --- Value instance methods ---

        @Test
        void valueLovelaceOf() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Value v = (Value)(Object) redeemer;
                            return v.lovelaceOf() == 5000000;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var value = simpleValue(5000000);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(value)));
            assertTrue(result.isSuccess(), "value.lovelaceOf() should return 5000000. Got: " + result);
        }

        @Test
        void valueIsEmptyTrue() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Value v = (Value)(Object) redeemer;
                            return v.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.map())));
            assertTrue(result.isSuccess(), "Empty value.isEmpty() should return true. Got: " + result);
        }

        @Test
        void valueIsEmptyFalse() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Value v = (Value)(Object) redeemer;
                            return !v.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var value = simpleValue(1000000);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(value)));
            assertTrue(result.isSuccess(), "Non-empty value.isEmpty() should return false. Got: " + result);
        }

        @Test
        void valueAssetOfFound() {
            // Pass value and policy/token as a ConstrData tuple in the redeemer
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            Value v = (Value)(Object) Builtins.headList(fields);
                            PlutusData policyId = Builtins.headList(Builtins.tailList(fields));
                            PlutusData tokenName = Builtins.headList(Builtins.tailList(Builtins.tailList(fields)));
                            long amount = v.assetOf(policyId, tokenName);
                            return amount == 42;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var value = multiAssetValue(2000000, new byte[]{1, 2, 3}, new byte[]{4, 5}, 42);
            var redeemer = PlutusData.constr(0, value,
                    PlutusData.bytes(new byte[]{1, 2, 3}), PlutusData.bytes(new byte[]{4, 5}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "value.assetOf() should return 42. Got: " + result);
        }

        @Test
        void valueAssetOfNotFound() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            Value v = (Value)(Object) Builtins.headList(fields);
                            PlutusData policyId = Builtins.headList(Builtins.tailList(fields));
                            PlutusData tokenName = Builtins.headList(Builtins.tailList(Builtins.tailList(fields)));
                            long amount = v.assetOf(policyId, tokenName);
                            return amount == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var value = multiAssetValue(2000000, new byte[]{1, 2, 3}, new byte[]{4, 5}, 42);
            // Pass non-matching policy/token
            var redeemer = PlutusData.constr(0, value,
                    PlutusData.bytes(new byte[]{9, 9, 9}), PlutusData.bytes(new byte[]{8, 8}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "value.assetOf() with non-existent policy should return 0. Got: " + result);
        }
    }

    // ====================================================================
    // Tuple2 / Tuple3 tests
    // ====================================================================

    @Nested
    class Tuple2Tests {

        @Test
        void constructAndAccessFirst() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple2;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple2 t = new Tuple2(Builtins.iData(BigInteger.valueOf(10)), Builtins.iData(BigInteger.valueOf(20)));
                            return Builtins.unIData(t.first()) == 10;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Tuple2.first() should return first element. Got: " + result);
        }

        @Test
        void constructAndAccessSecond() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple2;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple2 t = new Tuple2(Builtins.iData(BigInteger.valueOf(10)), Builtins.iData(BigInteger.valueOf(20)));
                            return Builtins.unIData(t.second()) == 20;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Tuple2.second() should return second element. Got: " + result);
        }

        @Test
        void fieldAccessBothFields() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple2;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple2 t = new Tuple2(Builtins.iData(BigInteger.valueOf(3)), Builtins.iData(BigInteger.valueOf(7)));
                            return Builtins.unIData(t.first()) + Builtins.unIData(t.second()) == 10;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Tuple2 field access should sum to 10. Got: " + result);
        }

        @Test
        void mathLibDivMod() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple2;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple2 result = MathLib.divMod(BigInteger.valueOf(17), BigInteger.valueOf(5));
                            long div = Builtins.unIData(result.first());
                            long rem = Builtins.unIData(result.second());
                            return div == 3 && rem == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "MathLib.divMod should return Tuple2(3, 2) for 17/5. Got: " + result);
        }

        @Test
        void mathLibQuotRem() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple2;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple2 result = MathLib.quotRem(BigInteger.valueOf(17), BigInteger.valueOf(5));
                            long q = Builtins.unIData(result.first());
                            long r = Builtins.unIData(result.second());
                            return q == 3 && r == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "MathLib.quotRem should return Tuple2(3, 2) for 17/5. Got: " + result);
        }
    }

    @Nested
    class Tuple3Tests {

        @Test
        void constructAndAccessFields() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple3;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple3 t = new Tuple3(Builtins.iData(BigInteger.valueOf(1)),
                                                  Builtins.iData(BigInteger.valueOf(2)),
                                                  Builtins.iData(BigInteger.valueOf(3)));
                            return Builtins.unIData(t.first()) == 1
                                && Builtins.unIData(t.second()) == 2
                                && Builtins.unIData(t.third()) == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Tuple3 field access should work. Got: " + result);
        }

        @Test
        void fieldAccessAllThree() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple3;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple3 t = new Tuple3(Builtins.iData(BigInteger.valueOf(10)),
                                                  Builtins.iData(BigInteger.valueOf(20)),
                                                  Builtins.iData(BigInteger.valueOf(30)));
                            return Builtins.unIData(t.first()) + Builtins.unIData(t.second()) + Builtins.unIData(t.third()) == 60;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Tuple3 field access should sum to 60. Got: " + result);
        }
    }

    // ====================================================================
    // JulcList tests
    // ====================================================================

    @Nested
    class JulcListTests {

        @Test
        void julcListResolvesAsListType() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<PlutusData> items = Builtins.unListData(redeemer);
                            return items.size() == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "JulcList should resolve same as List. Got: " + result);
        }

        @Test
        void julcListHeadAndTail() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<PlutusData> items = Builtins.unListData(redeemer);
                            PlutusData first = items.head();
                            JulcList<PlutusData> rest = items.tail();
                            return Builtins.unIData(first) == 10 && rest.size() == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(10), PlutusData.integer(20), PlutusData.integer(30));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "JulcList head/tail should work. Got: " + result);
        }

        @Test
        void listReverse() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            List<PlutusData> items = Builtins.unListData(redeemer);
                            List<PlutusData> rev = items.reverse();
                            return Builtins.unIData(rev.head()) == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.reverse() should put 3 at head. Got: " + result);
        }

        @Test
        void listConcat() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData fields = Builtins.constrFields(redeemer);
                            List<PlutusData> a = Builtins.unListData(Builtins.headList(fields));
                            List<PlutusData> b = Builtins.unListData(Builtins.headList(Builtins.tailList(fields)));
                            List<PlutusData> combined = a.concat(b);
                            return combined.size() == 5;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var a = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var b = PlutusData.list(PlutusData.integer(4), PlutusData.integer(5));
            var redeemer = PlutusData.constr(0, a, b);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.concat() should produce 5 elements. Got: " + result);
        }

        @Test
        void listConcatPreservesOrder() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData fields = Builtins.constrFields(redeemer);
                            List<PlutusData> a = Builtins.unListData(Builtins.headList(fields));
                            List<PlutusData> b = Builtins.unListData(Builtins.headList(Builtins.tailList(fields)));
                            List<PlutusData> combined = a.concat(b);
                            // [1,2] ++ [3,4] -> [1,2,3,4], head should be 1
                            long first = Builtins.unIData(combined.head());
                            long last = Builtins.unIData(combined.get(3));
                            return first == 1 && last == 4;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var a = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2));
            var b = PlutusData.list(PlutusData.integer(3), PlutusData.integer(4));
            var redeemer = PlutusData.constr(0, a, b);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "concat should preserve order: [1,2]++[3,4] -> head=1, idx3=4. Got: " + result);
        }

        @Test
        void listTake() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            List<PlutusData> items = Builtins.unListData(redeemer);
                            List<PlutusData> first2 = items.take(2);
                            return first2.size() == 2 && Builtins.unIData(first2.head()) == 10;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(10), PlutusData.integer(20), PlutusData.integer(30));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.take(2) should return first 2 elements. Got: " + result);
        }

        @Test
        void listDrop() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            List<PlutusData> items = Builtins.unListData(redeemer);
                            List<PlutusData> rest = items.drop(2);
                            return rest.size() == 1 && Builtins.unIData(rest.head()) == 30;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(10), PlutusData.integer(20), PlutusData.integer(30));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.drop(2) should skip first 2 elements. Got: " + result);
        }

        @Test
        void listPrependAutoWrap() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            List<BigInteger> items = Builtins.unListData(redeemer);
                            List<BigInteger> prepended = items.prepend(BigInteger.valueOf(99));
                            return prepended.size() == 4 && prepended.head() == 99;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.prepend(BigInteger) should auto-wrap with IData. Got: " + result);
        }

        @Test
        void julcListEmpty() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<PlutusData> empty = JulcList.empty();
                            return empty.isEmpty() && empty.size() == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "JulcList.empty() should create empty list. Got: " + result);
        }

        @Test
        void julcListOfSingleElement() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<PlutusData> items = JulcList.of(Builtins.iData(BigInteger.valueOf(42)));
                            return items.size() == 1 && Builtins.unIData(items.head()) == 42;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "JulcList.of(one) should create single-element list. Got: " + result);
        }

        @Test
        void julcListOfMultipleElements() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData a = Builtins.iData(BigInteger.valueOf(10));
                            PlutusData b = Builtins.iData(BigInteger.valueOf(20));
                            PlutusData c = Builtins.iData(BigInteger.valueOf(30));
                            JulcList<PlutusData> items = JulcList.of(a, b, c);
                            return items.size() == 3
                                && Builtins.unIData(items.head()) == 10
                                && Builtins.unIData(items.get(2)) == 30;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "JulcList.of(a,b,c) should create 3-element list in order. Got: " + result);
        }

        @Test
        void julcListEmptyThenPrepend() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<PlutusData> items = JulcList.<PlutusData>empty().prepend(Builtins.iData(BigInteger.valueOf(99)));
                            return items.size() == 1 && Builtins.unIData(items.head()) == 99;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "JulcList.empty().prepend() should create single-element list. Got: " + result);
        }
    }

    // ====================================================================
    // JulcMap instance method tests
    // ====================================================================

    @Nested
    class JulcMapTests {

        @Test
        void julcMapResolvesAsMapType() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.JulcMap;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) redeemer;
                            return m.size() == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "JulcMap should resolve same as Map. Got: " + result);
        }

        @Test
        void mapInsert() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            Map<PlutusData, PlutusData> updated = m.insert(Builtins.iData(BigInteger.valueOf(3)), Builtins.iData(BigInteger.valueOf(30)));
                            return updated.size() == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "map.insert() should add element. Got: " + result);
        }

        @Test
        void mapDelete() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            Map<PlutusData, PlutusData> updated = m.delete(Builtins.iData(BigInteger.valueOf(1)));
                            return updated.size() == 1;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "map.delete() should remove element. Got: " + result);
        }

        @Test
        void mapDeleteNonExistent() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            Map<PlutusData, PlutusData> updated = m.delete(Builtins.iData(BigInteger.valueOf(99)));
                            return updated.size() == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "map.delete() non-existent key should keep original size. Got: " + result);
        }
    }
}
