package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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

    /** Build a TxInfo with custom signatories at field index 8. */
    static PlutusData buildTxInfoWithSignatories(PlutusData... sigs) {
        return PlutusData.constr(0,
                PlutusData.list(),                                     // 0: inputs
                PlutusData.list(),                                     // 1: referenceInputs
                PlutusData.list(),                                     // 2: outputs
                PlutusData.integer(2000000),                           // 3: fee (lovelace)
                PlutusData.map(),                                      // 4: mint (empty)
                PlutusData.list(),                                     // 5: certificates
                PlutusData.map(),                                      // 6: withdrawals
                alwaysInterval(),                                      // 7: validRange
                PlutusData.list(sigs),                                 // 8: signatories
                PlutusData.map(),                                      // 9: redeemers
                PlutusData.map(),                                      // 10: datums
                PlutusData.bytes(new byte[32]),                        // 11: txId
                PlutusData.map(),                                      // 12: votes
                PlutusData.list(),                                     // 13: proposalProcedures
                PlutusData.constr(1),                                  // 14: currentTreasuryAmount (None)
                PlutusData.constr(1)                                   // 15: treasuryDonation (None)
        );
    }

    /** Build a TxInfo with a custom withdrawals map at field index 6. */
    static PlutusData buildTxInfoWithWithdrawals(PlutusData withdrawals) {
        return PlutusData.constr(0,
                PlutusData.list(),                                     // 0: inputs
                PlutusData.list(),                                     // 1: referenceInputs
                PlutusData.list(),                                     // 2: outputs
                PlutusData.integer(2000000),                           // 3: fee (lovelace)
                PlutusData.map(),                                      // 4: mint (empty)
                PlutusData.list(),                                     // 5: certificates
                withdrawals,                                           // 6: withdrawals
                alwaysInterval(),                                      // 7: validRange
                PlutusData.list(),                                     // 8: signatories
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

    /** Build Address: Constr(0, [PubKeyCredential(pkh), noStaking]). */
    static PlutusData buildAddress(byte[] pkh) {
        var pubKeyCred = PlutusData.constr(0, PlutusData.bytes(pkh));
        var noStaking = PlutusData.constr(1);
        return PlutusData.constr(0, pubKeyCred, noStaking);
    }

    /** Build a TxOut: Constr(0, [address, value, datum, refScript]). */
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

    static Program compileValidatorWithLibs(String validator, String... libs) {
        var compiler = new JulcCompiler(STDLIB::lookup);
        var result = compiler.compile(validator, List.of(libs));
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

        @Test
        void reverseWorks() {
            // Test ListsLib.reverse — build [1,2,3], reverse to [3,2,1], check head==3
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import com.bloxbean.cardano.julc.core.types.JulcList;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            JulcList<PlutusData> list = (JulcList)(Object) Builtins.mkCons(Builtins.iData(1),
                                Builtins.mkCons(Builtins.iData(2),
                                Builtins.mkCons(Builtins.iData(3), Builtins.mkNilData())));
                            JulcList<PlutusData> rev = ListsLib.reverse(list);
                            return Builtins.unIData(rev.head()) == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "reverse([1,2,3]) head should be 3. Got: " + result);
        }

        @Test
        void flattenPolicyWorks() {
            // Test flattenPolicy directly — flatten a single policy's inner map
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import com.bloxbean.cardano.julc.core.types.JulcList;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            // redeemer = Map[(policy, Map[(token, 100)])]
                            var outerPairs = Builtins.unMapData(redeemer);
                            var outerPair = Builtins.headList(outerPairs);
                            var policyData = Builtins.fstPair(outerPair);
                            PlutusData.MapData innerMap = (PlutusData.MapData) Builtins.sndPair(outerPair);
                            JulcList<PlutusData> acc = JulcList.empty();
                            JulcList<PlutusData> result = ValuesLib.flattenPolicy(policyData, innerMap, acc);
                            return !result.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var value = PlutusData.map(
                    new PlutusData.Pair(
                            PlutusData.bytes(new byte[]{1, 2, 3}),
                            PlutusData.map(
                                    new PlutusData.Pair(PlutusData.bytes(new byte[]{4, 5, 6}), PlutusData.integer(100))
                            )
                    )
            );
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(value)));
            assertTrue(result.isSuccess(), "flattenPolicy should return non-empty list. Got: " + result);
        }

        @Test
        void flattenReturnsNonEmptyList() {
            // Test calling ValuesLib.flatten as a library method
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            PlutusData.ListData flat = ValuesLib.flatten(redeemer);
                            return !Builtins.nullList(flat);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var value = PlutusData.map(
                    new PlutusData.Pair(
                            PlutusData.bytes(new byte[]{1, 2, 3}),
                            PlutusData.map(
                                    new PlutusData.Pair(PlutusData.bytes(new byte[]{4, 5, 6}), PlutusData.integer(100))
                            )
                    )
            );
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(value)));
            assertTrue(result.isSuccess(), "ValuesLib.flatten should return non-empty list. Got: " + result);
        }

        @Test
        void flattenWithAssetEntryCast() {
            // Test that flatten() result can be cast to JulcList<AssetEntry> for typed field access
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.AssetEntry;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            JulcList<AssetEntry> entries = (JulcList<AssetEntry>)(Object) ValuesLib.flatten(redeemer);
                            boolean found = false;
                            for (AssetEntry entry : entries) {
                                if (entry.amount().compareTo(BigInteger.valueOf(100)) == 0) {
                                    found = true;
                                }
                            }
                            return found;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var value = PlutusData.map(
                    new PlutusData.Pair(
                            PlutusData.bytes(new byte[]{1, 2, 3}),
                            PlutusData.map(
                                    new PlutusData.Pair(PlutusData.bytes(new byte[]{4, 5, 6}), PlutusData.integer(100))
                            )
                    )
            );
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(value)));
            assertTrue(result.isSuccess(), "flatten + AssetEntry cast should enable typed field access. Got: " + result);
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
                    import com.bloxbean.cardano.julc.core.types.JulcMap;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            JulcMap<PlutusData, PlutusData> map = (JulcMap)(Object) redeemer;
                            Optional<PlutusData> result = MapLib.lookup(map, Builtins.iData(1));
                            return result.isPresent();
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
                    import com.bloxbean.cardano.julc.core.types.JulcMap;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            // redeemer = Constr(0, [txOut, datumsMap])
                            PlutusData fields = Builtins.constrFields(redeemer);
                            TxOut txOut = Builtins.headList(fields);
                            JulcMap<PlutusData, PlutusData> datumsMap = (JulcMap)(Object) Builtins.headList(Builtins.tailList(fields));
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

        @Test
        void genericTuple2IntFields() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple2;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple2<BigInteger, BigInteger> t = new Tuple2<BigInteger, BigInteger>(BigInteger.valueOf(10), BigInteger.valueOf(20));
                            return t.first() == 10;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Generic Tuple2<BigInteger,BigInteger>.first() should auto-unwrap. Got: " + result);
        }

        @Test
        void genericTuple2ByteFields() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple2;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            byte[] a = Builtins.encodeUtf8("hello");
                            byte[] b = Builtins.encodeUtf8("world");
                            Tuple2<byte[], byte[]> t = new Tuple2<byte[], byte[]>(a, b);
                            return Builtins.equalsByteString(t.first(), a);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Generic Tuple2<byte[],byte[]>.first() should auto-unwrap via UnBData. Got: " + result);
        }

        @Test
        void genericTuple2MixedFields() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple2;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple2<BigInteger, byte[]> t = new Tuple2<BigInteger, byte[]>(BigInteger.valueOf(42), Builtins.encodeUtf8("ab"));
                            return t.first() == 42;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Generic Tuple2<BigInteger,byte[]>.first() should auto-unwrap integer. Got: " + result);
        }

        @Test
        void genericMathLibDivMod() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple2;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple2<BigInteger, BigInteger> result = MathLib.divMod(BigInteger.valueOf(17), BigInteger.valueOf(5));
                            return result.first() == 3 && result.second() == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Generic Tuple2 from MathLib.divMod should auto-unwrap. Got: " + result);
        }

        @Test
        void rawTuple2StillWorks() {
            // Verify backward compat: raw Tuple2 without generics + manual unwrap
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple2;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple2 t = new Tuple2(Builtins.iData(BigInteger.valueOf(10)), Builtins.iData(BigInteger.valueOf(20)));
                            return Builtins.unIData(t.first()) == 10 && Builtins.unIData(t.second()) == 20;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Raw Tuple2 with manual unwrap should still work. Got: " + result);
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

        @Test
        void genericTuple3AllFields() {
            var source = """
                    import com.bloxbean.cardano.julc.core.types.Tuple3;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Tuple3<BigInteger, byte[], BigInteger> t = new Tuple3<BigInteger, byte[], BigInteger>(
                                BigInteger.valueOf(10), Builtins.encodeUtf8("ab"), BigInteger.valueOf(30));
                            return t.first() == 10 && t.third() == 30;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "Generic Tuple3 should auto-unwrap typed fields. Got: " + result);
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

        // ================================================================
        // A. Field access → MapType method (double-unwrap bug fix tests)
        // ================================================================

        private PlutusData fieldAccessCtx(PlutusData withdrawals) {
            var txInfo = buildTxInfoWithWithdrawals(withdrawals);
            return PlutusData.constr(0, txInfo, PlutusData.integer(0),
                    PlutusData.constr(2, PlutusData.constr(0)));
        }

        private PlutusData twoEntryWithdrawals() {
            // Use integer keys for simplicity — allows tests to construct matching keys with Builtins.iData()
            return PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(100)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(200)));
        }

        @Test
        void mapFromFieldAccess_containsKey() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            PlutusData key = Builtins.iData(BigInteger.valueOf(1));
                            return w.containsKey(key);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "mapFromFieldAccess containsKey should find key. Got: " + result);
        }

        @Test
        void mapFromFieldAccess_containsKeyMissing() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            PlutusData key = Builtins.iData(BigInteger.valueOf(99));
                            return !w.containsKey(key);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "mapFromFieldAccess containsKey missing should return false. Got: " + result);
        }

        @Test
        void mapFromFieldAccess_isEmpty() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            return !w.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "mapFromFieldAccess isEmpty should be false for non-empty. Got: " + result);
        }

        @Test
        void mapFromFieldAccess_isEmptyTrue() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            return w.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(PlutusData.map());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "mapFromFieldAccess isEmpty should be true for empty map. Got: " + result);
        }

        @Test
        void mapFromFieldAccess_size() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            return w.size() == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "mapFromFieldAccess size should return 2. Got: " + result);
        }

        @Test
        void mapFromFieldAccess_get() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            PlutusData key = Builtins.iData(BigInteger.valueOf(1));
                            PlutusData result = w.get(key);
                            return Builtins.equalsData(result, Builtins.iData(BigInteger.valueOf(100)));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "mapFromFieldAccess get should return value 100. Got: " + result);
        }

        @Test
        void mapFromFieldAccess_keys() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            List<PlutusData> ks = w.keys();
                            return !Builtins.nullList(ks);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "mapFromFieldAccess keys should return non-empty list. Got: " + result);
        }

        @Test
        void mapFromFieldAccess_values() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            List<PlutusData> vs = w.values();
                            return !Builtins.nullList(vs);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "mapFromFieldAccess values should return non-empty list. Got: " + result);
        }

        // ================================================================
        // B. Cast-based → method (regression tests for existing behavior)
        // ================================================================

        @Test
        void mapCast_containsKeyFound() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            return m.containsKey(Builtins.iData(BigInteger.valueOf(1)));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "mapCast containsKey found. Got: " + result);
        }

        @Test
        void mapCast_containsKeyMissing() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            return !m.containsKey(Builtins.iData(BigInteger.valueOf(99)));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "mapCast containsKey missing. Got: " + result);
        }

        @Test
        void mapCast_get() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            PlutusData result = m.get(Builtins.iData(BigInteger.valueOf(1)));
                            return Builtins.equalsData(result, Builtins.iData(BigInteger.valueOf(10)));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "mapCast get should return value 10. Got: " + result);
        }

        @Test
        void mapCast_getMissing() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            PlutusData result = m.get(Builtins.iData(BigInteger.valueOf(99)));
                            return Builtins.equalsData(result, Builtins.iData(BigInteger.ONE));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertFalse(result.isSuccess(), "mapCast get missing should crash (VM error). Got: " + result);
        }

        @Test
        void mapCast_isEmpty() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            return !m.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "mapCast isEmpty non-empty. Got: " + result);
        }

        @Test
        void mapCast_isEmptyTrue() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            return m.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map();
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "mapCast isEmpty empty. Got: " + result);
        }

        @Test
        void mapCast_keys() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            List<PlutusData> ks = m.keys();
                            return !Builtins.nullList(ks);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "mapCast keys should return non-empty list. Got: " + result);
        }

        @Test
        void mapCast_values() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            List<PlutusData> vs = m.values();
                            return !Builtins.nullList(vs);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "mapCast values should return non-empty list. Got: " + result);
        }

        // ================================================================
        // C. Method chaining edge cases
        // ================================================================

        @Test
        void mapInsertThenContainsKey() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            Map<PlutusData, PlutusData> updated = m.insert(Builtins.iData(BigInteger.valueOf(3)), Builtins.iData(BigInteger.valueOf(30)));
                            return updated.containsKey(Builtins.iData(BigInteger.valueOf(3)));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "insert then containsKey should find new key. Got: " + result);
        }

        @Test
        void mapDeleteThenContainsKey() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            Map<PlutusData, PlutusData> updated = m.delete(Builtins.iData(BigInteger.valueOf(1)));
                            return !updated.containsKey(Builtins.iData(BigInteger.valueOf(1)));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "delete then containsKey should not find deleted key. Got: " + result);
        }

        @Test
        void mapInsertThenSize() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

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
            assertTrue(result.isSuccess(), "insert then size should be 3. Got: " + result);
        }

        @Test
        void mapDeleteThenSize() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

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
            assertTrue(result.isSuccess(), "delete then size should be 1. Got: " + result);
        }

        // ================================================================
        // D. For-each on MapType from field access
        // ================================================================

        @Test
        void forEachOnMapFromFieldAccess() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            int count = 0;
                            for (var entry : w) {
                                count = count + 1;
                            }
                            return count == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "forEachOnMapFromFieldAccess should count 2 entries. Got: " + result);
        }

        // ================================================================
        // E. Mixed paths: field access + method chaining
        // ================================================================

        @Test
        void mapFromFieldAccess_insertThenSize() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            PlutusData newKey = Builtins.iData(BigInteger.valueOf(5));
                            Map<PlutusData, PlutusData> updated = w.insert(newKey, Builtins.iData(BigInteger.valueOf(500)));
                            return updated.size() == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "mapFromFieldAccess insertThenSize should be 3. Got: " + result);
        }

        @Test
        void mapFromFieldAccess_deleteThenContainsKey() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            PlutusData key = Builtins.iData(BigInteger.valueOf(1));
                            Map<PlutusData, PlutusData> updated = w.delete(key);
                            return !updated.containsKey(key);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "mapFromFieldAccess deleteThenContainsKey should not find key. Got: " + result);
        }

        // ================================================================
        // F. Deep method chaining (no intermediate variables)
        // ================================================================

        @Test
        void mapInsertDeleteChain() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            return !m.insert(Builtins.iData(BigInteger.valueOf(3)), Builtins.iData(BigInteger.valueOf(30)))
                                      .delete(Builtins.iData(BigInteger.valueOf(1)))
                                      .containsKey(Builtins.iData(BigInteger.valueOf(1)));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "insert→delete→containsKey chain should work without intermediate vars. Got: " + result);
        }

        @Test
        void mapFieldAccessInsertDeleteChain() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            return !w.insert(Builtins.iData(BigInteger.valueOf(3)), Builtins.iData(BigInteger.valueOf(30)))
                                      .delete(Builtins.iData(BigInteger.valueOf(1)))
                                      .containsKey(Builtins.iData(BigInteger.valueOf(1)));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "field-access insert→delete→containsKey chain should work. Got: " + result);
        }

        @Test
        void mapInsertInsertSize() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            return m.insert(Builtins.iData(BigInteger.valueOf(3)), Builtins.iData(BigInteger.valueOf(30)))
                                     .insert(Builtins.iData(BigInteger.valueOf(4)), Builtins.iData(BigInteger.valueOf(40)))
                                     .size() == 4;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "double insert chain then size should be 4. Got: " + result);
        }

        // ================================================================
        // G. While loop with MapType accumulator
        // ================================================================

        @Test
        void whileLoopBuildingMap() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            int i = 0;
                            while (i < 3) {
                                i = i + 1;
                                m = m.insert(Builtins.iData(BigInteger.valueOf(i)), Builtins.iData(BigInteger.valueOf(i * 10)));
                            }
                            return m.size() == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(); // empty map
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "while loop building map via insert should produce size 3. Got: " + result);
        }

        // ================================================================
        // H. Cross-library MapLib call with field-access map (known limitation)
        // ================================================================

        @Disabled("Known limitation: MapLib static methods expect MapData, but field-access maps are pair lists. " +
                  "Calling MapLib.member(txInfo.withdrawals(), key) double-unwraps because the library body " +
                  "calls Builtins.unMapData() on an already-unwrapped pair list.")
        @Test
        void mapLibMemberOnFieldAccessMap_knownLimitation() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.MapLib;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            PlutusData key = Builtins.iData(BigInteger.valueOf(1));
                            return MapLib.member(w, key);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "MapLib.member on field-access map. Got: " + result);
        }

        // ================================================================
        // H. map.get() returns value directly, map.lookup() returns Optional
        // ================================================================

        @Test
        void mapGet_returnsValueDirectly() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            PlutusData val = m.get(Builtins.iData(BigInteger.valueOf(1)));
                            return Builtins.equalsData(val, Builtins.iData(BigInteger.valueOf(10)));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "mapGet should return value directly. Got: " + result);
        }

        @Test
        void mapGet_missingKey_crashes() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            PlutusData val = m.get(Builtins.iData(BigInteger.valueOf(99)));
                            return Builtins.equalsData(val, Builtins.iData(BigInteger.ONE));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertFalse(result.isSuccess(), "mapGet missing key should crash. Got: " + result);
        }

        @Test
        void mapLookup_returnsOptional() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            Optional<PlutusData> opt = m.lookup(Builtins.iData(BigInteger.valueOf(1)));
                            return opt.isPresent();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "mapLookup should return present Optional. Got: " + result);
        }

        @Test
        void mapLookup_missingKey_isEmpty() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            Optional<PlutusData> opt = m.lookup(Builtins.iData(BigInteger.valueOf(99)));
                            return opt.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "mapLookup missing key should return empty Optional. Got: " + result);
        }

        // ================================================================
        // I. For-each auto-unwrap tests
        // ================================================================

        @Test
        void forEach_integerList_autoUnwrap() {
            // Test auto-unwrapping of integer elements via map.values() which returns a typed list
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            List<PlutusData> vs = m.values();
                            BigInteger total = BigInteger.ZERO;
                            for (var v : vs) {
                                total = total.add(Builtins.unIData(v));
                            }
                            return total == 6;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(1)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(2)),
                    new PlutusData.Pair(PlutusData.integer(3), PlutusData.integer(3)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "forEach integer list via map.values() should sum to 6. Got: " + result);
        }

        @Test
        void forEach_byteStringList_autoUnwrap() {
            // Test for-each over signatories (List<PubKeyHash>) — ByteStringType elements
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            List<PubKeyHash> sigs = txInfo.signatories();
                            int count = 0;
                            for (var sig : sigs) {
                                count = count + 1;
                            }
                            return count == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            // Build a TxInfo with 2 signatories
            var txInfo = buildTxInfoWithSignatories(
                    PlutusData.bytes(new byte[]{1, 2, 3}),
                    PlutusData.bytes(new byte[]{4, 5, 6}));
            var ctx = PlutusData.constr(0, txInfo, PlutusData.integer(0),
                    PlutusData.constr(2, PlutusData.constr(0)));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "forEach bytestring list auto-unwrap should count 2. Got: " + result);
        }

        @Test
        void forEach_plutusDataList_unchanged() {
            // Test for-each over untyped (DataType) list — no auto-unwrap, backward compat
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Map<PlutusData, PlutusData> m = (Map<PlutusData, PlutusData>)(Object) redeemer;
                            List<PlutusData> ks = m.keys();
                            int count = 0;
                            for (var k : ks) {
                                count = count + 1;
                            }
                            return count == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
                    new PlutusData.Pair(PlutusData.integer(2), PlutusData.integer(20)),
                    new PlutusData.Pair(PlutusData.integer(3), PlutusData.integer(30)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "forEach PlutusData list unchanged should count 3. Got: " + result);
        }

        @Test
        void forEach_mapValues_autoUnwrap() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Map<PlutusData, PlutusData> w = txInfo.withdrawals();
                            List<PlutusData> vs = w.values();
                            BigInteger total = BigInteger.ZERO;
                            for (var v : vs) {
                                total = total.add(Builtins.unIData(v));
                            }
                            return total == 300;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var ctx = fieldAccessCtx(twoEntryWithdrawals());
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "forEach map values auto-unwrap should sum to 300. Got: " + result);
        }
    }

    // =========================================================================
    // ContainsPolicyEval — ValuesLib.containsPolicy() and value.containsPolicy()
    // =========================================================================

    @Nested
    class ContainsPolicyEval {

        /** Build a TxInfo with a custom mint value at field index 4. */
        static PlutusData buildTxInfoWithMint(PlutusData mint) {
            return PlutusData.constr(0,
                    PlutusData.list(),                                     // 0: inputs
                    PlutusData.list(),                                     // 1: referenceInputs
                    PlutusData.list(),                                     // 2: outputs
                    PlutusData.integer(2000000),                           // 3: fee
                    mint,                                                  // 4: mint
                    PlutusData.list(),                                     // 5: certificates
                    PlutusData.map(),                                      // 6: withdrawals
                    alwaysInterval(),                                      // 7: validRange
                    PlutusData.list(),                                     // 8: signatories
                    PlutusData.map(),                                      // 9: redeemers
                    PlutusData.map(),                                      // 10: datums
                    PlutusData.bytes(new byte[32]),                        // 11: txId
                    PlutusData.map(),                                      // 12: votes
                    PlutusData.list(),                                     // 13: proposalProcedures
                    PlutusData.constr(1),                                  // 14: currentTreasuryAmount (None)
                    PlutusData.constr(1)                                   // 15: treasuryDonation (None)
            );
        }

        // --- ValuesLib static method tests ---

        @Test
        void containsPolicyBytesFound() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            PlutusData value = Builtins.headList(fields);
                            byte[] policyId = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            return ValuesLib.containsPolicy(value, policyId);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var policy = new byte[]{1, 2, 3};
            var value = multiAssetValue(2000000, policy, new byte[]{4, 5}, 100);
            var redeemer = PlutusData.constr(0, value, PlutusData.bytes(policy));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "containsPolicy(bytes) found should be true. Got: " + result);
        }

        @Test
        void containsPolicyBytesNotFound() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            PlutusData value = Builtins.headList(fields);
                            byte[] policyId = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            return ValuesLib.containsPolicy(value, policyId);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var policy = new byte[]{1, 2, 3};
            var otherPolicy = new byte[]{9, 9, 9};
            var value = multiAssetValue(2000000, policy, new byte[]{4, 5}, 100);
            var redeemer = PlutusData.constr(0, value, PlutusData.bytes(otherPolicy));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertFalse(result.isSuccess(), "containsPolicy(bytes) not found should be false. Got: " + result);
        }

        @Test
        void containsPolicyDataFound() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            PlutusData value = Builtins.headList(fields);
                            PlutusData policyId = Builtins.headList(Builtins.tailList(fields));
                            return ValuesLib._containsPolicy(value, policyId);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var policy = new byte[]{1, 2, 3};
            var value = multiAssetValue(2000000, policy, new byte[]{4, 5}, 100);
            var redeemer = PlutusData.constr(0, value, PlutusData.bytes(policy));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "_containsPolicy(data) found should be true. Got: " + result);
        }

        @Test
        void containsPolicyEmptyValueReturnsFalse() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            PlutusData value = Builtins.headList(fields);
                            byte[] policyId = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            return ValuesLib.containsPolicy(value, policyId);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var emptyValue = PlutusData.map(); // empty value
            var redeemer = PlutusData.constr(0, emptyValue, PlutusData.bytes(new byte[]{1, 2, 3}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertFalse(result.isSuccess(), "containsPolicy on empty value should be false. Got: " + result);
        }

        // --- Value instance method tests ---

        @Test
        void valueInstanceContainsPolicyFound() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.ledger.api.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            Value value = (Value)(Object) Builtins.headList(fields);
                            PlutusData policyId = Builtins.headList(Builtins.tailList(fields));
                            return value.containsPolicy(policyId);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var policy = new byte[]{1, 2, 3};
            var value = multiAssetValue(2000000, policy, new byte[]{4, 5}, 100);
            var redeemer = PlutusData.constr(0, value, PlutusData.bytes(policy));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "value.containsPolicy() found should be true. Got: " + result);
        }

        @Test
        void valueInstanceContainsPolicyNotFound() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.ledger.api.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            Value value = (Value)(Object) Builtins.headList(fields);
                            PlutusData policyId = Builtins.headList(Builtins.tailList(fields));
                            return value.containsPolicy(policyId);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var policy = new byte[]{1, 2, 3};
            var otherPolicy = new byte[]{9, 9, 9};
            var value = multiAssetValue(2000000, policy, new byte[]{4, 5}, 100);
            var redeemer = PlutusData.constr(0, value, PlutusData.bytes(otherPolicy));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertFalse(result.isSuccess(), "value.containsPolicy() not found should be false. Got: " + result);
        }

        @Test
        void mintContainsPolicyChained() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.ledger.api.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Value mint = txInfo.mint();
                            return mint.containsPolicy(redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var policy = new byte[]{1, 2, 3};
            var mint = PlutusData.map(
                    new PlutusData.Pair(
                            PlutusData.bytes(policy),
                            PlutusData.map(
                                    new PlutusData.Pair(PlutusData.bytes(new byte[]{4, 5}), PlutusData.integer(100))
                            )
                    )
            );
            var txInfo = buildTxInfoWithMint(mint);
            var ctx = PlutusData.constr(0, txInfo, PlutusData.bytes(policy), PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "txInfo.mint().containsPolicy() should be true. Got: " + result);
        }

        @Test
        void paramPolicyIdContainsPolicyFound() {
            // Reproduces the exact CIP-113 BlacklistSpend failure:
            // @Param PolicyId is decoded to ByteStringType, but containsPolicy uses EqualsData
            // which needs Data args. Without wrapEncode, this fails with deserialization error.
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.ledger.*;

                    @Validator
                    class TestValidator {
                        @Param PolicyId blacklistCs;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Value mint = txInfo.mint();
                            return mint.containsPolicy(blacklistCs);
                        }
                    }
                    """;
            var compiler = new JulcCompiler(STDLIB::lookup);
            var compiled = compiler.compile(source);
            assertFalse(compiled.hasErrors(), "Compilation failed: " + compiled);
            assertTrue(compiled.isParameterized(), "Should be parameterized");

            var policy = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
                    15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
            var concrete = compiled.program().applyParams(PlutusData.bytes(policy));

            var mint = PlutusData.map(
                    new PlutusData.Pair(
                            PlutusData.bytes(policy),
                            PlutusData.map(
                                    new PlutusData.Pair(PlutusData.bytes(new byte[]{4, 5}), PlutusData.integer(100))
                            )
                    )
            );
            var txInfo = buildTxInfoWithMint(mint);
            var ctx = PlutusData.constr(0, txInfo, PlutusData.integer(0), PlutusData.integer(0));
            var result = vm.evaluateWithArgs(concrete, List.of(ctx));
            assertTrue(result.isSuccess(), "@Param PolicyId + mint.containsPolicy() should succeed. Got: " + result);
        }

        @Test
        void paramPolicyIdContainsPolicyNotFound() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    import com.bloxbean.cardano.julc.ledger.*;

                    @Validator
                    class TestValidator {
                        @Param PolicyId blacklistCs;

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            Value mint = txInfo.mint();
                            return mint.containsPolicy(blacklistCs);
                        }
                    }
                    """;
            var compiler = new JulcCompiler(STDLIB::lookup);
            var compiled = compiler.compile(source);
            assertFalse(compiled.hasErrors(), "Compilation failed: " + compiled);

            var policy = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
                    15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
            var otherPolicy = new byte[]{28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18, 17, 16, 15,
                    14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
            var concrete = compiled.program().applyParams(PlutusData.bytes(policy));

            var mint = PlutusData.map(
                    new PlutusData.Pair(
                            PlutusData.bytes(otherPolicy),
                            PlutusData.map(
                                    new PlutusData.Pair(PlutusData.bytes(new byte[]{4, 5}), PlutusData.integer(100))
                            )
                    )
            );
            var txInfo = buildTxInfoWithMint(mint);
            var ctx = PlutusData.constr(0, txInfo, PlutusData.integer(0), PlutusData.integer(0));
            var result = vm.evaluateWithArgs(concrete, List.of(ctx));
            assertFalse(result.isSuccess(), "@Param PolicyId + mint.containsPolicy() not found should fail. Got: " + result);
        }
    }

    // =========================================================================
    // HOF Instance Methods + Lambda Type Inference
    // =========================================================================

    @Nested
    class ListHofTests {

        // --- A. Instance HOFs with explicit typed lambdas (regression-safe) ---

        @Test
        void listMapWithTypedLambda() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            List<PlutusData> items = Builtins.unListData(redeemer);
                            List<PlutusData> mapped = items.map((PlutusData x) -> Builtins.iData(Builtins.unIData(x) + 1));
                            return Builtins.unIData(mapped.head()) == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.map with typed lambda should work. Got: " + result);
        }

        @Test
        void listFilterWithTypedLambda() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            List<PlutusData> items = Builtins.unListData(redeemer);
                            List<PlutusData> filtered = items.filter((PlutusData x) -> Builtins.unIData(x) > 2);
                            return filtered.size() == 1;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.filter with typed lambda should work. Got: " + result);
        }

        @Test
        void listAnyWithTypedLambda() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            List<PlutusData> items = Builtins.unListData(redeemer);
                            return items.any((PlutusData x) -> Builtins.unIData(x) > 2);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.any with typed lambda should work. Got: " + result);
        }

        @Test
        void listAllWithTypedLambda() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            List<PlutusData> items = Builtins.unListData(redeemer);
                            return items.all((PlutusData x) -> Builtins.unIData(x) > 0);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.all with typed lambda should work. Got: " + result);
        }

        @Test
        void listFindWithTypedLambda() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            List<PlutusData> items = Builtins.unListData(redeemer);
                            PlutusData found = items.find((PlutusData x) -> Builtins.unIData(x) == 3);
                            return Builtins.constrTag(found) == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.find with typed lambda should return Some. Got: " + result);
        }

        // --- B. Instance HOFs with type inference (core feature) ---

        @Test
        void listMapInferred() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            JulcList<PlutusData> mapped = items.map(x -> x + 1);
                            return Builtins.unIData(mapped.head()) == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.map with inferred types should work. Got: " + result);
        }

        @Test
        void listFilterInferred() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            JulcList<BigInteger> filtered = items.filter(x -> x > 2);
                            return filtered.size() == 1;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.filter with inferred types should work. Got: " + result);
        }

        @Test
        void listAnyInferred() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            return items.any(x -> x > 2);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.any with inferred types should work. Got: " + result);
        }

        @Test
        void listAllInferred() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            return items.all(x -> x > 0);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.all with inferred types should work. Got: " + result);
        }

        // --- C. Variable capture (closures) ---

        @Test
        void listFilterWithCapturedVariable() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            BigInteger threshold = BigInteger.valueOf(2);
                            JulcList<BigInteger> filtered = items.filter(x -> x > threshold);
                            return filtered.size() == 1;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "Lambda with captured variable should work. Got: " + result);
        }

        @Test
        void listMapWithCapturedVariable() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            BigInteger offset = BigInteger.valueOf(10);
                            JulcList<PlutusData> mapped = items.map(x -> x + offset);
                            return Builtins.unIData(mapped.head()) == 11;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "Lambda with captured variable in map should work. Got: " + result);
        }

        // --- D. Static HOF type inference ---

        @Test
        void listsLibMapInferred() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.ListsLib;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            JulcList<PlutusData> mapped = ListsLib.map(items, x -> x + 1);
                            return Builtins.unIData(mapped.head()) == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "ListsLib.map with inferred types should work. Got: " + result);
        }

        @Test
        void listsLibFilterInferred() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.ListsLib;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            JulcList<BigInteger> filtered = ListsLib.filter(items, x -> x > 2);
                            return filtered.size() == 1;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "ListsLib.filter with inferred types should work. Got: " + result);
        }

        // --- E. Chained HOFs ---

        @Test
        void chainedFilterThenMap() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            JulcList<PlutusData> result = items.filter(x -> x > 1).map(x -> x * 2);
                            return Builtins.unIData(result.head()) == 4;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "Chained filter().map() should work. Got: " + result);
        }

        // --- F. Block body lambda ---

        @Test
        void listMapWithBlockBody() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            JulcList<PlutusData> mapped = items.map(x -> {
                                BigInteger y = x + 1;
                                return y * 2;
                            });
                            return Builtins.unIData(mapped.head()) == 4;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.map with block body lambda should work. Got: " + result);
        }

        // --- G. Edge cases ---

        @Test
        void listMapEmptyList() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            JulcList<BigInteger> mapped = items.map(x -> x + 1);
                            return mapped.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list();
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "map on empty list should return empty. Got: " + result);
        }

        @Test
        void listFilterAllMatch() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            JulcList<BigInteger> filtered = items.filter(x -> x > 0);
                            return filtered.size() == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "filter where all match should return same-size list. Got: " + result);
        }

        @Test
        void listFilterNoneMatch() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            JulcList<BigInteger> filtered = items.filter(x -> x > 100);
                            return filtered.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "filter where none match should return empty list. Got: " + result);
        }

        // --- H. Additional edge cases (review-identified gaps) ---

        @Test
        void listMapReturningBool() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            JulcList<PlutusData> bools = items.map(x -> x > 0);
                            // bools should be [ConstrData(1,[]), ConstrData(0,[]), ConstrData(1,[])]
                            // ConstrData tag 1 = True, tag 0 = False
                            BigInteger firstTag = Builtins.constrTag(bools.head());
                            return firstTag == 1;
                        }
                    }
                    """;
            var program = compileValidator(source);
            // [5, -2, 3] — first is positive (True), second is negative (False), third is positive (True)
            var redeemer = PlutusData.list(PlutusData.integer(5), PlutusData.integer(-2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "map returning Bool should wrapEncode to ConstrData. Got: " + result);
        }

        @Test
        void listFindInferred() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            JulcList<BigInteger> items = Builtins.unListData(redeemer);
                            PlutusData found = items.find(x -> x > 2);
                            // found should be Some(IData(3)) = Constr(0, [IData(3)])
                            return Builtins.constrTag(found) == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.find with inferred type should return Some. Got: " + result);
        }

        // --- G. ByteStringType HOF double-unwrap fix ---

        @Test
        void listAnyInferredByteStringType() {
            // Bug: untyped lambda on JulcList<PubKeyHash> caused double UnBData
            // HOF inference inserts Let("sig", UnBData(sig), body), then .hash() adds another UnBData
            // PubKeyHash maps to ByteStringType — list elements are BData(bytes) on-chain
            // Redeemer = Constr(0, [list_of_bdata, target_bytes])
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var fields = Builtins.sndPair(Builtins.unConstrData(redeemer));
                            JulcList<PubKeyHash> sigs = Builtins.unListData(Builtins.headList(fields));
                            byte[] target = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            return sigs.any(sig -> Builtins.equalsByteString((byte[])(Object) sig.hash(), target));
                        }
                    }
                    """;
            var program = compileValidator(source);
            // PubKeyHash = ByteStringType → list elements are BData-wrapped bytes
            var sigsList = PlutusData.list(
                    PlutusData.bytes(new byte[]{1, 2, 3}),
                    PlutusData.bytes(new byte[]{4, 5, 6}));
            var redeemer = PlutusData.constr(0, sigsList, PlutusData.bytes(new byte[]{1, 2, 3}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.any with inferred ByteStringType should not double-unwrap. Got: " + result);
        }

        @Test
        void listFilterInferredByteStringType() {
            // Same double-unwrap bug via filter HOF
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var fields = Builtins.sndPair(Builtins.unConstrData(redeemer));
                            JulcList<PubKeyHash> sigs = Builtins.unListData(Builtins.headList(fields));
                            byte[] target = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            JulcList<PubKeyHash> matched = sigs.filter(sig -> Builtins.equalsByteString((byte[])(Object) sig.hash(), target));
                            return matched.size() == 1;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var sigsList = PlutusData.list(
                    PlutusData.bytes(new byte[]{1, 2, 3}),
                    PlutusData.bytes(new byte[]{4, 5, 6}));
            var redeemer = PlutusData.constr(0, sigsList, PlutusData.bytes(new byte[]{1, 2, 3}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.filter with inferred ByteStringType should not double-unwrap. Got: " + result);
        }

        @Test
        void listAnyExplicitTypedByteString() {
            // Regression: explicit typed lambda (PubKeyHash sig) -> must still work (no HOF unwrap)
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var fields = Builtins.sndPair(Builtins.unConstrData(redeemer));
                            JulcList<PubKeyHash> sigs = Builtins.unListData(Builtins.headList(fields));
                            byte[] target = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            return sigs.any((PubKeyHash sig) -> Builtins.equalsByteString((byte[])(Object) sig.hash(), target));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var sigsList = PlutusData.list(
                    PlutusData.bytes(new byte[]{1, 2, 3}),
                    PlutusData.bytes(new byte[]{4, 5, 6}));
            var redeemer = PlutusData.constr(0, sigsList, PlutusData.bytes(new byte[]{1, 2, 3}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.any with explicit typed ByteStringType lambda should work. Got: " + result);
        }

        @Test
        void byteStringUnregisteredFieldAccessor() {
            // Bug 2: unregistered no-arg method on ByteStringType (e.g. TokenName.name()) should generate UnBData
            // Simulates a for-each loop over a list where elements map to ByteStringType
            // Redeemer = Constr(0, [list_of_tokennames, target_bytes])
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var fields = Builtins.sndPair(Builtins.unConstrData(redeemer));
                            JulcList<TokenName> names = Builtins.unListData(Builtins.headList(fields));
                            byte[] target = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            boolean found = false;
                            for (var tn : names) {
                                if (Builtins.equalsByteString(tn.name(), target)) {
                                    found = true;
                                }
                            }
                            return found;
                        }
                    }
                    """;
            var program = compileValidator(source);
            // TokenName maps to ByteStringType → list elements are BData-wrapped bytes
            var namesList = PlutusData.list(
                    PlutusData.bytes(new byte[]{65, 66}),
                    PlutusData.bytes(new byte[]{67, 68}));
            var redeemer = PlutusData.constr(0, namesList, PlutusData.bytes(new byte[]{65, 66}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "Unregistered field accessor on ByteStringType should generate UnBData. Got: " + result);
        }

        @Test
        void listsLibAnyInferredByteStringType() {
            // Same double-unwrap bug via static ListsLib.any() path
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.lib.ListsLib;
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var fields = Builtins.sndPair(Builtins.unConstrData(redeemer));
                            JulcList<PubKeyHash> sigs = Builtins.unListData(Builtins.headList(fields));
                            byte[] target = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            return ListsLib.any(sigs, sig -> Builtins.equalsByteString((byte[])(Object) sig.hash(), target));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var sigsList = PlutusData.list(
                    PlutusData.bytes(new byte[]{1, 2, 3}),
                    PlutusData.bytes(new byte[]{4, 5, 6}));
            var redeemer = PlutusData.constr(0, sigsList, PlutusData.bytes(new byte[]{1, 2, 3}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "ListsLib.any with inferred ByteStringType should not double-unwrap. Got: " + result);
        }

        // --- H. Nested HOF save/restore of hofUnwrappedVars ---

        @Test
        void nestedHofByteStringType() {
            // Validates save/restore of hofUnwrappedVars for nested lambda-in-lambda.
            // Outer filter has ByteStringType param, inner any also has ByteStringType param.
            // Both must avoid double-unwrap independently.
            // Redeemer = Constr(0, [list_of_bdata_hashes, list_of_bdata_whitelist])
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var fields = Builtins.sndPair(Builtins.unConstrData(redeemer));
                            JulcList<PubKeyHash> sigs = Builtins.unListData(Builtins.headList(fields));
                            JulcList<PubKeyHash> whitelist = Builtins.unListData(Builtins.headList(Builtins.tailList(fields)));
                            // Nested HOF: filter sigs where sig is in whitelist
                            JulcList<PubKeyHash> matched = sigs.filter(sig ->
                                whitelist.any(w -> Builtins.equalsByteString((byte[])(Object) sig.hash(), (byte[])(Object) w.hash()))
                            );
                            return matched.size() == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var sigsList = PlutusData.list(
                    PlutusData.bytes(new byte[]{1, 2, 3}),
                    PlutusData.bytes(new byte[]{4, 5, 6}),
                    PlutusData.bytes(new byte[]{7, 8, 9}));
            var whiteList = PlutusData.list(
                    PlutusData.bytes(new byte[]{1, 2, 3}),
                    PlutusData.bytes(new byte[]{7, 8, 9}));
            var redeemer = PlutusData.constr(0, sigsList, whiteList);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "Nested HOF with ByteStringType should handle save/restore correctly. Got: " + result);
        }

        // --- I. Other ByteStringType-mapped types in HOF context ---

        @Test
        void listAnyInferredScriptHash() {
            // ScriptHash maps to ByteStringType — same double-unwrap fix must apply
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var fields = Builtins.sndPair(Builtins.unConstrData(redeemer));
                            JulcList<ScriptHash> hashes = Builtins.unListData(Builtins.headList(fields));
                            byte[] target = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            return hashes.any(h -> Builtins.equalsByteString((byte[])(Object) h.hash(), target));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var hashList = PlutusData.list(
                    PlutusData.bytes(new byte[]{10, 20, 30}),
                    PlutusData.bytes(new byte[]{40, 50, 60}));
            var redeemer = PlutusData.constr(0, hashList, PlutusData.bytes(new byte[]{10, 20, 30}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.any with inferred ScriptHash should not double-unwrap. Got: " + result);
        }

        @Test
        void listAnyInferredTxId() {
            // TxId maps to ByteStringType — same double-unwrap fix must apply
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.ledger.api.*;
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var fields = Builtins.sndPair(Builtins.unConstrData(redeemer));
                            JulcList<TxId> txIds = Builtins.unListData(Builtins.headList(fields));
                            byte[] target = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            return txIds.any(tx -> Builtins.equalsByteString((byte[])(Object) tx.hash(), target));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var txIdList = PlutusData.list(
                    PlutusData.bytes(new byte[]{11, 22}),
                    PlutusData.bytes(new byte[]{33, 44}));
            var redeemer = PlutusData.constr(0, txIdList, PlutusData.bytes(new byte[]{11, 22}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "list.any with inferred TxId should not double-unwrap. Got: " + result);
        }

        // --- J. @NewType byte[] field access in HOF lambda ---

        @Test
        void newTypeByteArrayInHofLambda() {
            // @NewType byte[] maps to ByteStringType.
            // In HOF lambda, field accessor (.id()) should be identity for HOF-unwrapped var.
            // Redeemer = Constr(0, [list_of_bdata, target_bytes])
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    @Validator
                    class TestValidator {
                        @NewType
                        record AssetId(byte[] id) {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var fields = Builtins.sndPair(Builtins.unConstrData(redeemer));
                            JulcList<AssetId> assets = Builtins.unListData(Builtins.headList(fields));
                            byte[] target = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            return assets.any(a -> Builtins.equalsByteString(a.id(), target));
                        }
                    }
                    """;
            var program = compileValidator(source);
            // @NewType byte[] → list elements are BData-wrapped bytes
            var assetList = PlutusData.list(
                    PlutusData.bytes(new byte[]{0x01, 0x02}),
                    PlutusData.bytes(new byte[]{0x03, 0x04}));
            var redeemer = PlutusData.constr(0, assetList, PlutusData.bytes(new byte[]{0x01, 0x02}));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "@NewType byte[] field accessor in HOF lambda should work. Got: " + result);
        }
    }

    // =========================================================================
    // Library Method Return Type Resolution — tests for resolveMethodCallReturnType
    // covering static @OnchainLibrary method calls via LibraryMethodRegistry lookup
    // =========================================================================

    @Nested
    class LibraryMethodReturnTypeResolution {

        @Test
        void libraryReturningLongDirectComparison() {
            // Library returns long; validator uses result directly in == comparison.
            // Verifies resolveMethodCallReturnType returns IntegerType → EqualsInteger
            var userLib = """
                    package com.example;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @OnchainLibrary
                    public class MyLib {
                        public static long countElements(PlutusData list) {
                            var cursor = Builtins.unListData(list);
                            long count = 0;
                            while (!Builtins.nullList(cursor)) {
                                count = count + 1;
                                cursor = Builtins.tailList(cursor);
                            }
                            return count;
                        }
                    }
                    """;
            var validator = """
                    import com.example.MyLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return MyLib.countElements(redeemer) == 3;
                        }
                    }
                    """;
            var program = compileValidatorWithLibs(validator, userLib);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
            var evalResult = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(evalResult.isSuccess(), "Library returning long in direct == should use EqualsInteger. Got: " + evalResult);
        }

        @Test
        void libraryReturningLongInArithmetic() {
            // Library returns long; validator uses result in arithmetic then comparison.
            // Verifies library result type feeds into AddInteger then EqualsInteger
            var userLib = """
                    package com.example;
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

                    @OnchainLibrary
                    public class MyLib {
                        public static long doubleValue(long x) {
                            return x + x;
                        }
                    }
                    """;
            var validator = """
                    import com.example.MyLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return MyLib.doubleValue(5) + 1 == 11;
                        }
                    }
                    """;
            var program = compileValidatorWithLibs(validator, userLib);
            var evalResult = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(evalResult.isSuccess(), "Library returning long in arithmetic should use AddInteger. Got: " + evalResult);
        }

        @Test
        void libraryReturningBooleanDirectUse() {
            // Library returns boolean; validator returns it directly.
            // Verifies BoolType return resolution
            var userLib = """
                    package com.example;
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

                    @OnchainLibrary
                    public class MyLib {
                        public static boolean isPositive(long x) {
                            return x > 0;
                        }
                    }
                    """;
            var validator = """
                    import com.example.MyLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return MyLib.isPositive(42);
                        }
                    }
                    """;
            var program = compileValidatorWithLibs(validator, userLib);
            var evalResult = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(evalResult.isSuccess(), "Library returning boolean should resolve BoolType. Got: " + evalResult);
        }

        @Test
        void chainedFieldAccessOnLibraryRecordResult() {
            // CRITICAL: Library returns TxOut; validator chains .address() on the result.
            // Without the fix, resolveMethodCallReturnType returns DataType → line 769
            // has NO inferPirType fallback → falls through to generateFieldAccessFromMethod
            // → produces broken PIR ("Unbound variable: .address")
            var userLib = """
                    package com.example;
                    import com.bloxbean.cardano.julc.core.PlutusData;
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.ledger.TxOut;

                    @OnchainLibrary
                    public class MyLib {
                        public static TxOut getFirstOutput(PlutusData outputsList) {
                            return Builtins.headList(Builtins.unListData(outputsList));
                        }
                    }
                    """;
            var validator = """
                    import com.example.MyLib;
                    import com.bloxbean.cardano.julc.ledger.Address;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            Address addr = MyLib.getFirstOutput(redeemer).address();
                            return addr == addr;
                        }
                    }
                    """;
            var program = compileValidatorWithLibs(validator, userLib);
            // Build a TxOut as PlutusData: Constr(0, [address, value, datum, refScript])
            var address = PlutusData.constr(0,
                    PlutusData.constr(0, PlutusData.bytes(new byte[]{1, 2, 3})),
                    PlutusData.constr(1));
            var txOut = PlutusData.constr(0, address, simpleValue(2000000),
                    PlutusData.constr(0), PlutusData.constr(1));
            var redeemer = PlutusData.list(txOut);
            var evalResult = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(evalResult.isSuccess(),
                    "Chained .address() on library-returned TxOut must resolve RecordType. Got: " + evalResult);
        }

        @Test
        void nestedLibraryCallTypeResolution() {
            // Two library methods; validator nests one inside the other.
            // Verifies recursive resolveMethodCallReturnType for nested library calls
            var userLib = """
                    package com.example;
                    import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

                    @OnchainLibrary
                    public class MyLib {
                        public static long doubleIt(long x) {
                            return x + x;
                        }
                        public static long addOne(long x) {
                            return x + 1;
                        }
                    }
                    """;
            var validator = """
                    import com.example.MyLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return MyLib.addOne(MyLib.doubleIt(5)) == 11;
                        }
                    }
                    """;
            var program = compileValidatorWithLibs(validator, userLib);
            var evalResult = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(evalResult.isSuccess(), "Nested library calls should resolve types recursively. Got: " + evalResult);
        }
    }

    // =========================================================================
    // ByteStringLib — toHex and intToDecimalString
    // =========================================================================

    @Nested
    class ByteStringLibEval {

        // All tests pack input + expected into redeemer as Constr(0, [input, expected])
        // and use mockCtx(redeemer) for proper PlutusV3 single-arg evaluation.

        @Test
        void toHexDeadBeef() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            byte[] input = Builtins.unBData(Builtins.headList(fields));
                            byte[] expected = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            byte[] hex = ByteStringLib.toHex(input);
                            return Builtins.equalsByteString(hex, expected);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(0,
                    PlutusData.bytes(new byte[]{(byte) 0xDE, (byte) 0xAD}),
                    PlutusData.bytes("dead".getBytes()));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "toHex([0xDE,0xAD]) should produce 'dead'. Got: " + result);
        }

        @Test
        void toHexZeroPadding() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            byte[] input = Builtins.unBData(Builtins.headList(fields));
                            byte[] expected = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            byte[] hex = ByteStringLib.toHex(input);
                            return Builtins.equalsByteString(hex, expected);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(0,
                    PlutusData.bytes(new byte[]{0x00, (byte) 0xFF}),
                    PlutusData.bytes("00ff".getBytes()));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "toHex([0x00,0xFF]) should produce '00ff'. Got: " + result);
        }

        @Test
        void toHexEmptyInput() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] hex = ByteStringLib.toHex(Builtins.emptyByteString());
                            return Builtins.lengthOfByteString(hex) == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "toHex([]) should produce empty. Got: " + result);
        }

        @Test
        void intToDecimalStringZero() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] result = ByteStringLib.intToDecimalString(BigInteger.ZERO);
                            byte[] expected = Builtins.unBData(redeemer);
                            return Builtins.equalsByteString(result, expected);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes("0".getBytes());
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "intToDecimalString(0) should produce '0'. Got: " + result);
        }

        @Test
        void intToDecimalString42() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            BigInteger n = Builtins.unIData(Builtins.headList(fields));
                            byte[] expected = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            byte[] result = ByteStringLib.intToDecimalString(n);
                            return Builtins.equalsByteString(result, expected);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(0,
                    PlutusData.integer(42),
                    PlutusData.bytes("42".getBytes()));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "intToDecimalString(42) should produce '42'. Got: " + result);
        }

        @Test
        void intToDecimalString12345() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            BigInteger n = Builtins.unIData(Builtins.headList(fields));
                            byte[] expected = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                            byte[] result = ByteStringLib.intToDecimalString(n);
                            return Builtins.equalsByteString(result, expected);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(0,
                    PlutusData.integer(12345),
                    PlutusData.bytes("12345".getBytes()));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "intToDecimalString(12345) should produce '12345'. Got: " + result);
        }
    }

    // =========================================================================
    // TypeFriendlyAliases — asBytes/asInteger/asList/asMap + unBData return type
    // =========================================================================

    @Nested
    class TypeFriendlyAliases {

        @Test
        void asBytesExtractsFromBytesData() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            byte[] raw = Builtins.asBytes(redeemer);
                            return Builtins.lengthOfByteString(raw) == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes(new byte[]{1, 2, 3});
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "asBytes should extract bytes. Got: " + result);
        }

        @Test
        void asIntegerExtractsFromIntData() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            BigInteger n = Builtins.asInteger(redeemer);
                            return n.compareTo(BigInteger.valueOf(42)) == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.integer(42);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "asInteger should extract integer. Got: " + result);
        }

        @Test
        void asListExtractsFromListData() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var list = Builtins.asList(redeemer);
                            return !Builtins.nullList(list);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "asList should extract list. Got: " + result);
        }

        @Test
        void asMapExtractsFromMapData() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var m = Builtins.asMap(redeemer);
                            return !Builtins.nullList(m);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.map(
                    new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "asMap should extract map. Got: " + result);
        }

        @Test
        void unBDataReturnsBytes() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            byte[] raw = Builtins.unBData(redeemer);
                            return Builtins.lengthOfByteString(raw) == 4;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes(new byte[]{10, 20, 30, 40});
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "unBData should return byte[]. Got: " + result);
        }

        @Test
        void backwardCompatRedundantCast() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            byte[] raw = (byte[])(Object) Builtins.unBData(redeemer);
                            return Builtins.lengthOfByteString(raw) == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes(new byte[]{(byte) 0xAB, (byte) 0xCD});
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "Redundant cast should still compile. Got: " + result);
        }

        @Test
        void constrTagExtractsTag() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            long tag = Builtins.constrTag(redeemer);
                            return tag == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(2, PlutusData.integer(99));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "constrTag should extract tag. Got: " + result);
        }

        @Test
        void constrFieldsExtractsFields() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            BigInteger val = Builtins.unIData(Builtins.headList(fields));
                            return val.compareTo(BigInteger.valueOf(42)) == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(0, PlutusData.integer(42));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "constrFields should extract fields. Got: " + result);
        }

        @Test
        void asBytesWithHeadList() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var fields = Builtins.constrFields(redeemer);
                            byte[] hash = Builtins.asBytes(Builtins.headList(fields));
                            return Builtins.lengthOfByteString(hash) == 28;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "asBytes(headList(fields)) should work. Got: " + result);
        }

        @Test
        void varTypeInferenceWithAsBytes() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            var raw = Builtins.asBytes(redeemer);
                            return Builtins.equalsByteString(raw, raw);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes(new byte[]{1, 2, 3});
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(redeemer)));
            assertTrue(result.isSuccess(), "var inference with asBytes should work. Got: " + result);
        }
    }

    // =========================================================================
    // ContextsLib Typed Returns — DX-friendly API
    // =========================================================================

    @Nested
    class ContextsLibTypedReturns {

        // --- Data construction helpers ---

        static PlutusData buildScriptAddress(byte[] scriptHash) {
            var scriptCred = PlutusData.constr(1, PlutusData.bytes(scriptHash));
            var noStaking = PlutusData.constr(1);
            return PlutusData.constr(0, scriptCred, noStaking);
        }

        static PlutusData buildTxOutRef(byte[] txId, long index) {
            return PlutusData.constr(0, PlutusData.bytes(txId), PlutusData.integer(index));
        }

        static PlutusData buildTxInInfo(PlutusData txOutRef, PlutusData txOut) {
            return PlutusData.constr(0, txOutRef, txOut);
        }

        /** Build a ScriptContext: Constr(0, [txInfo, redeemer, scriptInfo]) */
        static PlutusData buildScriptContext(PlutusData txInfo, PlutusData redeemer, PlutusData scriptInfo) {
            return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
        }

        /** Build a SpendingScript ScriptInfo: Constr(1, [txOutRef, optionalDatum]) */
        static PlutusData buildSpendingScriptInfo(PlutusData txOutRef, PlutusData optDatum) {
            return PlutusData.constr(1, txOutRef, optDatum);
        }

        /** Build a MintingScript ScriptInfo: Constr(0, [policyId]) */
        static PlutusData buildMintingScriptInfo(byte[] policyId) {
            return PlutusData.constr(0, PlutusData.bytes(policyId));
        }

        /** Build a TxInfo with inputs, outputs, and datums map. All 16 fields. */
        static PlutusData buildFullTxInfo(PlutusData[] inputs, PlutusData[] outputs, PlutusData datumsMap) {
            return PlutusData.constr(0,
                    PlutusData.list(inputs),                               // 0: inputs
                    PlutusData.list(),                                     // 1: referenceInputs
                    PlutusData.list(outputs),                              // 2: outputs
                    PlutusData.integer(2000000),                           // 3: fee
                    PlutusData.map(),                                      // 4: mint
                    PlutusData.list(),                                     // 5: certificates
                    PlutusData.map(),                                      // 6: withdrawals
                    alwaysInterval(),                                      // 7: validRange
                    PlutusData.list(),                                     // 8: signatories
                    PlutusData.map(),                                      // 9: redeemers
                    datumsMap,                                             // 10: datums
                    PlutusData.bytes(new byte[32]),                        // 11: txId
                    PlutusData.map(),                                      // 12: votes
                    PlutusData.list(),                                     // 13: proposalProcedures
                    PlutusData.constr(1),                                  // 14: currentTreasuryAmount (None)
                    PlutusData.constr(1)                                   // 15: treasuryDonation (None)
            );
        }

        // --- 1. getSpendingDatum ---

        @Test
        void getSpendingDatumReturnsOptionalPresent() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Optional<PlutusData> datum = ContextsLib.getSpendingDatum(ctx);
                            return datum.isPresent();
                        }
                    }
                    """;
            var program = compileValidator(source);

            var txOutRef = buildTxOutRef(new byte[]{1, 2, 3}, 0);
            var optDatum = PlutusData.constr(0, PlutusData.integer(42)); // Some(42)
            var scriptInfo = buildSpendingScriptInfo(txOutRef, optDatum);
            var txInfo = buildFullTxInfo(new PlutusData[0], new PlutusData[0], PlutusData.map());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "getSpendingDatum should return present Optional. Got: " + result);
        }

        @Test
        void getSpendingDatumReturnsOptionalEmpty() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Optional<PlutusData> datum = ContextsLib.getSpendingDatum(ctx);
                            return datum.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);

            var txOutRef = buildTxOutRef(new byte[]{1, 2, 3}, 0);
            var optDatum = PlutusData.constr(1); // None
            var scriptInfo = buildSpendingScriptInfo(txOutRef, optDatum);
            var txInfo = buildFullTxInfo(new PlutusData[0], new PlutusData[0], PlutusData.map());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "getSpendingDatum should return empty Optional for no-datum spending. Got: " + result);
        }

        @Test
        void getSpendingDatumReturnsEmptyForMinting() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Optional<PlutusData> datum = ContextsLib.getSpendingDatum(ctx);
                            return datum.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);

            var scriptInfo = buildMintingScriptInfo(new byte[]{10, 20, 30});
            var txInfo = buildFullTxInfo(new PlutusData[0], new PlutusData[0], PlutusData.map());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "getSpendingDatum should return empty for MintingScript. Got: " + result);
        }

        // --- 2. findOwnInput ---

        @Test
        void findOwnInputReturnsOptionalPresent() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Optional<TxInInfo> ownInputOpt = ContextsLib.findOwnInput(ctx);
                            return ownInputOpt.isPresent();
                        }
                    }
                    """;
            var program = compileValidator(source);

            var txOutRef = buildTxOutRef(new byte[]{1, 2, 3}, 0);
            var scriptAddr = buildScriptAddress(new byte[]{7, 8, 9});
            var txOut = buildTxOut(scriptAddr, lovelaceValue(5000000));
            var txInInfo = buildTxInInfo(txOutRef, txOut);

            var optDatum = PlutusData.constr(1); // None
            var scriptInfo = buildSpendingScriptInfo(txOutRef, optDatum);
            var txInfo = buildFullTxInfo(new PlutusData[]{txInInfo}, new PlutusData[0], PlutusData.map());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "findOwnInput should find matching input. Got: " + result);
        }

        @Test
        void findOwnInputReturnsEmptyForMinting() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Optional<TxInInfo> ownInputOpt = ContextsLib.findOwnInput(ctx);
                            return ownInputOpt.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);

            var scriptInfo = buildMintingScriptInfo(new byte[]{10, 20, 30});
            var txInfo = buildFullTxInfo(new PlutusData[0], new PlutusData[0], PlutusData.map());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "findOwnInput should return empty for MintingScript. Got: " + result);
        }

        @Test
        void findOwnInputFieldAccess() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            Optional<TxInInfo> ownInputOpt = ContextsLib.findOwnInput(ctx);
                            TxInInfo ownInput = ownInputOpt.get();
                            Address addr = ownInput.resolved().address();
                            // Verify the address matches (equalsData with redeemer which holds expected address)
                            return Builtins.equalsData(addr, redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);

            var txOutRef = buildTxOutRef(new byte[]{1, 2, 3}, 0);
            var scriptAddr = buildScriptAddress(new byte[]{7, 8, 9});
            var txOut = buildTxOut(scriptAddr, lovelaceValue(5000000));
            var txInInfo = buildTxInInfo(txOutRef, txOut);

            var optDatum = PlutusData.constr(1); // None
            var scriptInfo = buildSpendingScriptInfo(txOutRef, optDatum);
            var txInfo = buildFullTxInfo(new PlutusData[]{txInInfo}, new PlutusData[0], PlutusData.map());
            var ctx = buildScriptContext(txInfo, scriptAddr, scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "findOwnInput should allow typed field access. Got: " + result);
        }

        // --- 3. ownHash ---

        @Test
        void ownHashReturnsByteArrayMinting() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            byte[] hash = ContextsLib.ownHash(ctx);
                            // Compare with redeemer which holds expected policy id
                            return Builtins.equalsByteString(hash, Builtins.unBData(redeemer));
                        }
                    }
                    """;
            var program = compileValidator(source);

            var policyId = new byte[]{10, 20, 30, 40};
            var scriptInfo = buildMintingScriptInfo(policyId);
            var txInfo = buildFullTxInfo(new PlutusData[0], new PlutusData[0], PlutusData.map());
            var ctx = buildScriptContext(txInfo, PlutusData.bytes(policyId), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "ownHash should return policyId for MintingScript. Got: " + result);
        }

        @Test
        void ownHashReturnsByteArraySpending() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            byte[] hash = ContextsLib.ownHash(ctx);
                            // Compare with redeemer which holds expected script hash
                            return Builtins.equalsByteString(hash, Builtins.unBData(redeemer));
                        }
                    }
                    """;
            var program = compileValidator(source);

            var scriptHash = new byte[]{7, 8, 9};
            var txOutRef = buildTxOutRef(new byte[]{1, 2, 3}, 0);
            var scriptAddr = buildScriptAddress(scriptHash);
            var txOut = buildTxOut(scriptAddr, lovelaceValue(5000000));
            var txInInfo = buildTxInInfo(txOutRef, txOut);

            var optDatum = PlutusData.constr(1); // None
            var scriptInfo = buildSpendingScriptInfo(txOutRef, optDatum);
            var txInfo = buildFullTxInfo(new PlutusData[]{txInInfo}, new PlutusData[0], PlutusData.map());
            var ctx = buildScriptContext(txInfo, PlutusData.bytes(scriptHash), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "ownHash should return script hash for SpendingScript. Got: " + result);
        }

        // --- 4. getContinuingOutputs ---

        @Test
        void getContinuingOutputsReturnsTypedList() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            List<TxOut> continuing = ContextsLib.getContinuingOutputs(ctx);
                            return ListsLib.length(continuing) == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var scriptHash = new byte[]{7, 8, 9};
            var txOutRef = buildTxOutRef(new byte[]{1, 2, 3}, 0);
            var scriptAddr = buildScriptAddress(scriptHash);
            var otherAddr = buildAddress(new byte[]{4, 5, 6});
            var txOut = buildTxOut(scriptAddr, lovelaceValue(5000000));
            var txInInfo = buildTxInInfo(txOutRef, txOut);

            // 2 outputs to own address, 1 to other
            var out1 = buildTxOut(scriptAddr, lovelaceValue(3000000));
            var out2 = buildTxOut(otherAddr, lovelaceValue(1000000));
            var out3 = buildTxOut(scriptAddr, lovelaceValue(2000000));

            var optDatum = PlutusData.constr(1);
            var scriptInfo = buildSpendingScriptInfo(txOutRef, optDatum);
            var txInfo = buildFullTxInfo(new PlutusData[]{txInInfo}, new PlutusData[]{out1, out2, out3}, PlutusData.map());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "getContinuingOutputs should return 2 outputs. Got: " + result);
        }

        // --- 5. valueSpent ---

        @Test
        void valueSpentReturnsTypedList() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ContextsLib.getTxInfo(ctx);
                            List<Value> values = ContextsLib.valueSpent(txInfo);
                            return ListsLib.length(values) == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var addr1 = buildAddress(new byte[]{1, 2, 3});
            var addr2 = buildAddress(new byte[]{4, 5, 6});
            var txOutRef1 = buildTxOutRef(new byte[]{10}, 0);
            var txOutRef2 = buildTxOutRef(new byte[]{20}, 0);
            var txOut1 = buildTxOut(addr1, lovelaceValue(3000000));
            var txOut2 = buildTxOut(addr2, lovelaceValue(5000000));
            var in1 = buildTxInInfo(txOutRef1, txOut1);
            var in2 = buildTxInInfo(txOutRef2, txOut2);

            var mintingInfo = buildMintingScriptInfo(new byte[]{99});
            var txInfo = buildFullTxInfo(new PlutusData[]{in1, in2}, new PlutusData[0], PlutusData.map());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0), mintingInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "valueSpent should return 2 values. Got: " + result);
        }

        // --- 6. valuePaid ---

        @Test
        void valuePaidReturnsTypedList() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ContextsLib.getTxInfo(ctx);
                            // redeemer holds the target address
                            List<Value> values = ContextsLib.valuePaid(txInfo, redeemer);
                            return ListsLib.length(values) == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var addr1 = buildAddress(new byte[]{1, 2, 3});
            var addr2 = buildAddress(new byte[]{4, 5, 6});
            var out1 = buildTxOut(addr1, lovelaceValue(1000000));
            var out2 = buildTxOut(addr1, lovelaceValue(2000000));
            var out3 = buildTxOut(addr2, lovelaceValue(3000000));

            var mintingInfo = buildMintingScriptInfo(new byte[]{99});
            var txInfo = buildFullTxInfo(new PlutusData[0], new PlutusData[]{out1, out2, out3}, PlutusData.map());
            var ctx = buildScriptContext(txInfo, addr1, mintingInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "valuePaid should return 2 matching values. Got: " + result);
        }

        // --- 7. scriptOutputsAt ---

        @Test
        void scriptOutputsAtReturnsTypedList() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ContextsLib.getTxInfo(ctx);
                            // redeemer holds the script hash as bytes
                            byte[] sh = Builtins.unBData(redeemer);
                            List<TxOut> outputs = ContextsLib.scriptOutputsAt(txInfo, sh);
                            return ListsLib.length(outputs) == 2;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var scriptHash = new byte[]{7, 8, 9};
            var scriptAddr = buildScriptAddress(scriptHash);
            var pubKeyAddr = buildAddress(new byte[]{1, 2, 3});
            var out1 = buildTxOut(scriptAddr, lovelaceValue(1000000));
            var out2 = buildTxOut(pubKeyAddr, lovelaceValue(2000000));
            var out3 = buildTxOut(scriptAddr, lovelaceValue(3000000));

            var mintingInfo = buildMintingScriptInfo(new byte[]{99});
            var txInfo = buildFullTxInfo(new PlutusData[0], new PlutusData[]{out1, out2, out3}, PlutusData.map());
            var ctx = buildScriptContext(txInfo, PlutusData.bytes(scriptHash), mintingInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "scriptOutputsAt should return 2 script outputs. Got: " + result);
        }

        @Test
        void scriptOutputsAtEmptyForPubKey() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ContextsLib.getTxInfo(ctx);
                            byte[] sh = Builtins.unBData(redeemer);
                            List<TxOut> outputs = ContextsLib.scriptOutputsAt(txInfo, sh);
                            return ListsLib.length(outputs) == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);

            var pubKeyAddr = buildAddress(new byte[]{1, 2, 3});
            var out1 = buildTxOut(pubKeyAddr, lovelaceValue(1000000));

            var mintingInfo = buildMintingScriptInfo(new byte[]{99});
            var txInfo = buildFullTxInfo(new PlutusData[0], new PlutusData[]{out1}, PlutusData.map());
            var ctx = buildScriptContext(txInfo, PlutusData.bytes(new byte[]{7, 8, 9}), mintingInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "scriptOutputsAt should return empty for pubkey addresses. Got: " + result);
        }

        // --- 8. findDatum ---

        @Test
        void findDatumReturnsOptionalPresent() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ContextsLib.getTxInfo(ctx);
                            // redeemer holds the datum hash to search for
                            Optional<PlutusData> datum = ContextsLib.findDatum(txInfo, redeemer);
                            return datum.isPresent();
                        }
                    }
                    """;
            var program = compileValidator(source);

            var datumHash = PlutusData.bytes(new byte[]{1, 2, 3});
            var datumValue = PlutusData.integer(42);
            var datumsMap = PlutusData.map(new PlutusData.Pair(datumHash, datumValue));

            var mintingInfo = buildMintingScriptInfo(new byte[]{99});
            var txInfo = buildFullTxInfo(new PlutusData[0], new PlutusData[0], datumsMap);
            var ctx = buildScriptContext(txInfo, datumHash, mintingInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "findDatum should find matching datum. Got: " + result);
        }

        @Test
        void findDatumReturnsOptionalEmpty() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ContextsLib.getTxInfo(ctx);
                            Optional<PlutusData> datum = ContextsLib.findDatum(txInfo, redeemer);
                            return datum.isEmpty();
                        }
                    }
                    """;
            var program = compileValidator(source);

            var datumHash = PlutusData.bytes(new byte[]{1, 2, 3});
            var datumsMap = PlutusData.map(
                    new PlutusData.Pair(PlutusData.bytes(new byte[]{9, 9, 9}), PlutusData.integer(99)));

            var mintingInfo = buildMintingScriptInfo(new byte[]{99});
            var txInfo = buildFullTxInfo(new PlutusData[0], new PlutusData[0], datumsMap);
            var ctx = buildScriptContext(txInfo, datumHash, mintingInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "findDatum should return empty for non-matching hash. Got: " + result);
        }
    }

    // =========================================================================
    // 22. StringGetBytes — String.getBytes() → EncodeUtf8
    // =========================================================================

    @Nested
    class StringGetBytes {

        @Test
        void getBytesProducesCorrectBytes() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] name = "hello".getBytes();
                            return Builtins.equalsByteString(name, Builtins.encodeUtf8("hello"));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "\"hello\".getBytes() should equal encodeUtf8(\"hello\"). Got: " + result);
        }

        @Test
        void emptyStringGetBytes() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] empty = "".getBytes();
                            return Builtins.lengthOfByteString(empty) == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "\"\".getBytes() should be empty. Got: " + result);
        }

        @Test
        void staticFinalByteConstant() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        static final byte[] TOKEN_NAME = "TOKEN".getBytes();

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return Builtins.lengthOfByteString(TOKEN_NAME) == 5;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "static final byte[] from .getBytes() should work. Got: " + result);
        }
    }

    // =========================================================================
    // 23. ByteArrayLiteral — new byte[]{0x48, 0x45} → ByteString constant
    // =========================================================================

    @Nested
    class ByteArrayLiteral {

        @Test
        void byteArrayLiteralCompiles() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] marker = new byte[]{0x48, 0x45, 0x4C};
                            return Builtins.lengthOfByteString(marker) == 3;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "new byte[]{0x48, 0x45, 0x4C} should compile and have length 3. Got: " + result);
        }

        @Test
        void emptyByteArrayLiteral() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] empty = new byte[]{};
                            return Builtins.lengthOfByteString(empty) == 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "new byte[]{} should compile to empty ByteString. Got: " + result);
        }

        @Test
        void staticFinalByteArrayLiteral() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        static final byte[] MARKER = new byte[]{0x46, 0x41, 0x43};

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return Builtins.equalsByteString(MARKER, new byte[]{0x46, 0x41, 0x43});
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "static final byte[] literal should equal inline literal. Got: " + result);
        }

        @Test
        void byteArrayLiteralEqualsGetBytes() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] fromLiteral = new byte[]{72, 69, 76};
                            byte[] fromString = "HEL".getBytes();
                            return Builtins.equalsByteString(fromLiteral, fromString);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "byte[] literal should equal String.getBytes() for same chars. Got: " + result);
        }
    }

    // =========================================================================
    // 24. Utf8ToIntegerEval — ByteStringLib.utf8ToInteger() compile + eval
    // =========================================================================

    @Nested
    class Utf8ToIntegerEval {

        @Test
        void parsesSimpleNumber() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] bs = "42".getBytes();
                            BigInteger n = ByteStringLib.utf8ToInteger(bs);
                            return n.equals(BigInteger.valueOf(42));
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "utf8ToInteger(\"42\".getBytes()) should equal 42. Got: " + result);
        }

        @Test
        void roundtripWithIntToDecimalString() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            BigInteger original = BigInteger.valueOf(12345);
                            byte[] encoded = ByteStringLib.intToDecimalString(original);
                            BigInteger decoded = ByteStringLib.utf8ToInteger(encoded);
                            return decoded.equals(original);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(PlutusData.integer(0))));
            assertTrue(result.isSuccess(), "utf8ToInteger(intToDecimalString(12345)) should roundtrip. Got: " + result);
        }
    }

    // =========================================================================
    // 25. FlattenTypedEval — ValuesLib.flattenTyped() compile + eval
    // =========================================================================

    @Nested
    class FlattenTypedEval {

        @Test
        void flattenTypedReturnsAssetEntries() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.core.types.AssetEntry;
                    import com.bloxbean.cardano.julc.core.types.JulcList;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            JulcList<AssetEntry> entries = ValuesLib.flattenTyped(redeemer);
                            boolean found = false;
                            for (AssetEntry entry : entries) {
                                if (entry.amount().compareTo(BigInteger.valueOf(100)) == 0) {
                                    found = true;
                                }
                            }
                            return found;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var value = PlutusData.map(
                    new PlutusData.Pair(
                            PlutusData.bytes(new byte[]{1, 2, 3}),
                            PlutusData.map(
                                    new PlutusData.Pair(PlutusData.bytes(new byte[]{4, 5, 6}), PlutusData.integer(100))
                            )
                    )
            );
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(value)));
            assertTrue(result.isSuccess(), "flattenTyped should return typed AssetEntry list. Got: " + result);
        }
    }

    // =========================================================================
    // 26. OwnHashContainsPolicyCompat — ownHash + containsPolicy cross-library
    // =========================================================================

    @Nested
    class OwnHashContainsPolicyCompat {

        @Test
        void ownHashWithContainsPolicyMinting() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import com.bloxbean.cardano.julc.ledger.*;

                    @MintingPolicy
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ContextsLib.getTxInfo(ctx);
                            byte[] ownPolicy = (byte[])(Object) ContextsLib.ownHash(ctx);
                            Value mint = txInfo.mint();
                            return ValuesLib.containsPolicy(mint, ownPolicy);
                        }
                    }
                    """;
            var program = compileValidator(source);

            var policyId = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
            var mintValue = PlutusData.map(
                    new PlutusData.Pair(PlutusData.bytes(policyId),
                            PlutusData.map(new PlutusData.Pair(PlutusData.bytes("token".getBytes()), PlutusData.integer(1)))));

            var txInfo = buildTxInfoWithMint(mintValue);
            var mintingInfo = PlutusData.constr(0, PlutusData.bytes(policyId)); // MintingScript(policyId)
            var ctx = PlutusData.constr(0, txInfo, PlutusData.integer(0), mintingInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "ownHash + containsPolicy should work for minting. Got: " + result);
        }

        @Test
        void ownHashWithAssetOfMinting() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;
                    import com.bloxbean.cardano.julc.stdlib.lib.*;
                    import com.bloxbean.cardano.julc.ledger.*;

                    @MintingPolicy
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ContextsLib.getTxInfo(ctx);
                            byte[] ownPolicy = (byte[])(Object) ContextsLib.ownHash(ctx);
                            Value mint = txInfo.mint();
                            BigInteger qty = ValuesLib.assetOf(mint, ownPolicy, "token".getBytes());
                            return qty.equals(BigInteger.ONE);
                        }
                    }
                    """;
            var program = compileValidator(source);

            var policyId = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
            var mintValue = PlutusData.map(
                    new PlutusData.Pair(PlutusData.bytes(policyId),
                            PlutusData.map(new PlutusData.Pair(PlutusData.bytes("token".getBytes()), PlutusData.integer(1)))));

            var txInfo = buildTxInfoWithMint(mintValue);
            var mintingInfo = PlutusData.constr(0, PlutusData.bytes(policyId));
            var ctx = PlutusData.constr(0, txInfo, PlutusData.integer(0), mintingInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "ownHash + assetOf should work for minting. Got: " + result);
        }

        /** Build a TxInfo with custom mint at field 4. */
        static PlutusData buildTxInfoWithMint(PlutusData mint) {
            return PlutusData.constr(0,
                    PlutusData.list(),                                     // 0: inputs
                    PlutusData.list(),                                     // 1: referenceInputs
                    PlutusData.list(),                                     // 2: outputs
                    PlutusData.integer(2000000),                           // 3: fee
                    mint,                                                  // 4: mint
                    PlutusData.list(),                                     // 5: certificates
                    PlutusData.map(),                                      // 6: withdrawals
                    alwaysInterval(),                                      // 7: validRange
                    PlutusData.list(),                                     // 8: signatories
                    PlutusData.map(),                                      // 9: redeemers
                    PlutusData.map(),                                      // 10: datums
                    PlutusData.bytes(new byte[32]),                        // 11: txId
                    PlutusData.map(),                                      // 12: votes
                    PlutusData.list(),                                     // 13: proposalProcedures
                    PlutusData.constr(1),                                  // 14: currentTreasuryAmount (None)
                    PlutusData.constr(1)                                   // 15: treasuryDonation (None)
            );
        }
    }

    // =========================================================================
    // 28. MergedValueLovelaceOf — ValuesLib.lovelaceOf() on multi-asset values
    // =========================================================================

    @Nested
    class MergedValueLovelaceOf {

        @Test
        void lovelaceOfMultiAssetValue() {
            // On-chain test: pass a multi-asset value (ADA first) and verify lovelaceOf extracts ADA amount
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
            // Build a multi-asset value with ADA first (as the Cardano ledger serializes)
            var policy = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
            var value = multiAssetValue(5000000, policy, new byte[]{0x01}, 42);
            var result = vm.evaluateWithArgs(program, List.of(mockCtx(value)));
            assertTrue(result.isSuccess(), "lovelaceOf on multi-asset value should extract 5000000. Got: " + result);
        }
    }
}
