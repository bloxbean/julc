package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.text.UplcParser;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.JavaVmProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CostModelParser} — round-trip parsing, custom values, and validation.
 */
class CostModelParserTest {

    @Test
    void parse_defaultValues_roundTrip() {
        // Build flat array from defaults, parse back, verify selected costs match
        long[] flat = CostModelParser.defaultToFlatArray();
        assertEquals(CostModelParser.PV10_PARAM_COUNT, flat.length);

        var parsed = CostModelParser.parse(flat);

        // Verify machine costs
        var defaultMc = DefaultCostModel.defaultMachineCosts();
        var parsedMc = parsed.machineCosts();
        assertEquals(defaultMc.startupCpu(), parsedMc.startupCpu());
        assertEquals(defaultMc.startupMem(), parsedMc.startupMem());
        assertEquals(defaultMc.varCpu(), parsedMc.varCpu());
        assertEquals(defaultMc.varMem(), parsedMc.varMem());
        assertEquals(defaultMc.lamCpu(), parsedMc.lamCpu());
        assertEquals(defaultMc.applyCpu(), parsedMc.applyCpu());
        assertEquals(defaultMc.forceCpu(), parsedMc.forceCpu());
        assertEquals(defaultMc.delayCpu(), parsedMc.delayCpu());
        assertEquals(defaultMc.constCpu(), parsedMc.constCpu());
        assertEquals(defaultMc.builtinCpu(), parsedMc.builtinCpu());
        assertEquals(defaultMc.constrCpu(), parsedMc.constrCpu());
        assertEquals(defaultMc.constrMem(), parsedMc.constrMem());
        assertEquals(defaultMc.caseCpu(), parsedMc.caseCpu());
        assertEquals(defaultMc.caseMem(), parsedMc.caseMem());
        assertEquals(defaultMc, parsedMc);

        // Verify selected builtin costs via cost function evaluation
        var defaultBcm = DefaultCostModel.defaultBuiltinCostModel();
        var parsedBcm = parsed.builtinCostModel();

        // AddInteger CPU: MaxSize(100788, 420)
        var addCpu = parsedBcm.get(DefaultFun.AddInteger).cpu();
        assertEquals(defaultBcm.get(DefaultFun.AddInteger).cpu().apply(10, 20),
                addCpu.apply(10, 20));

        // DivideInteger CPU: ConstAboveDiagonal (8 params)
        var divCpu = parsedBcm.get(DefaultFun.DivideInteger).cpu();
        assertEquals(defaultBcm.get(DefaultFun.DivideInteger).cpu().apply(5, 10),
                divCpu.apply(5, 10));
        assertEquals(defaultBcm.get(DefaultFun.DivideInteger).cpu().apply(10, 5),
                divCpu.apply(10, 5));

        // DivideInteger Mem: SubtractedSizes(0, 1, 1) — tests swap handling
        var divMem = parsedBcm.get(DefaultFun.DivideInteger).mem();
        assertEquals(defaultBcm.get(DefaultFun.DivideInteger).mem().apply(10, 5),
                divMem.apply(10, 5));
        assertEquals(defaultBcm.get(DefaultFun.DivideInteger).mem().apply(5, 10),
                divMem.apply(5, 10));

        // EqualsByteString CPU: LinearOnDiagonal(3 params)
        var eqBsCpu = parsedBcm.get(DefaultFun.EqualsByteString).cpu();
        assertEquals(defaultBcm.get(DefaultFun.EqualsByteString).cpu().apply(10, 10),
                eqBsCpu.apply(10, 10));
        assertEquals(defaultBcm.get(DefaultFun.EqualsByteString).cpu().apply(10, 20),
                eqBsCpu.apply(10, 20));

        // BLS12-381 (V3 section)
        var blsG1Add = parsedBcm.get(DefaultFun.Bls12_381_G1_add);
        assertEquals(defaultBcm.get(DefaultFun.Bls12_381_G1_add).cpu().apply(),
                blsG1Add.cpu().apply());

        // AndByteString (Plomin section): LinearInYAndZ
        var andCpu = parsedBcm.get(DefaultFun.AndByteString).cpu();
        assertEquals(defaultBcm.get(DefaultFun.AndByteString).cpu().apply(0, 10, 20),
                andCpu.apply(0, 10, 20));

        // Ripemd_160 (last Plomin builtin)
        var ripemdCpu = parsedBcm.get(DefaultFun.Ripemd_160).cpu();
        assertEquals(defaultBcm.get(DefaultFun.Ripemd_160).cpu().apply(32),
                ripemdCpu.apply(32));
    }

    @Test
    void parse_allBuiltinCostsMatch() {
        // Comprehensive check: for every builtin in DefaultCostModel, verify parsed matches
        long[] flat = CostModelParser.defaultToFlatArray();
        var parsed = CostModelParser.parse(flat);

        var defaultBcm = DefaultCostModel.defaultBuiltinCostModel();
        var parsedBcm = parsed.builtinCostModel();

        long[] testSizes = {1, 5, 10, 20, 100};

        for (var fun : DefaultFun.values()) {
            var defaultPair = defaultBcm.get(fun);
            if (defaultPair == null) continue;

            var parsedPair = parsedBcm.get(fun);
            assertNotNull(parsedPair, "Missing cost pair for " + fun);

            // Test with 4-arg sizes (covers LinearInU which needs 4th arg)
            for (long s1 : testSizes) {
                for (long s2 : testSizes) {
                    assertEquals(defaultPair.cpu().apply(s1, s2, s1, s2),
                            parsedPair.cpu().apply(s1, s2, s1, s2),
                            fun + " CPU mismatch at sizes " + s1 + "," + s2);
                    assertEquals(defaultPair.mem().apply(s1, s2, s1, s2),
                            parsedPair.mem().apply(s1, s2, s1, s2),
                            fun + " Mem mismatch at sizes " + s1 + "," + s2);
                }
            }
        }
    }

    @Test
    void parse_customValues_affectsBudget() {
        // Build flat array from defaults, double all values, parse, evaluate, verify different budget
        long[] defaultFlat = CostModelParser.defaultToFlatArray();
        long[] doubled = new long[defaultFlat.length];
        for (int i = 0; i < defaultFlat.length; i++) {
            doubled[i] = defaultFlat[i] * 2;
        }

        var javaProvider = new JavaVmProvider();
        var program = UplcParser.parseProgram("(program 1.0.0 (con integer 42))");

        // Evaluate with defaults
        var defaultResult = javaProvider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertTrue(defaultResult.isSuccess());
        long defaultCpu = defaultResult.budgetConsumed().cpuSteps();

        // Set doubled cost model and evaluate
        javaProvider.setCostModelParams(doubled, PlutusLanguage.PLUTUS_V3, 10, 0);
        var customResult = javaProvider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertTrue(customResult.isSuccess());
        long customCpu = customResult.budgetConsumed().cpuSteps();

        // Doubled costs should produce doubled budget
        assertEquals(defaultCpu * 2, customCpu,
                "Doubled cost model should produce doubled budget");
    }

    @Test
    void parse_wrongArrayLength_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                CostModelParser.parse(new long[10]));
        assertThrows(IllegalArgumentException.class, () ->
                CostModelParser.parse(new long[296]));
    }

    @Test
    void parse_longerArray_accepted() {
        // Longer arrays should parse without error at PV10, using first 297
        long[] flat = new long[400];
        long[] defaultFlat = CostModelParser.defaultToFlatArray();
        System.arraycopy(defaultFlat, 0, flat, 0, 297);

        var parsed = CostModelParser.parse(flat);
        assertNotNull(parsed);
        assertEquals(DefaultCostModel.defaultMachineCosts(), parsed.machineCosts());
    }

    @Test
    void toFlatArray_length() {
        long[] flat = CostModelParser.defaultToFlatArray();
        assertEquals(297, flat.length);
    }

    @Test
    void toFlatArray_pv11_length() {
        long[] flat = CostModelParser.defaultToFlatArray(11);
        assertEquals(350, flat.length);
    }

    // === PV11 round-trip tests ===

    @Test
    void parse_pv11_defaultValues_roundTrip() {
        // Build PV11 flat array from defaults, parse back, verify all costs match
        long[] flat = CostModelParser.defaultToFlatArray(11);
        assertEquals(CostModelParser.PV11_PARAM_COUNT, flat.length);

        var parsed = CostModelParser.parse(flat, 11);
        assertNotNull(parsed);

        // Machine costs should match
        assertEquals(DefaultCostModel.defaultMachineCosts(), parsed.machineCosts());

        // Verify PV11-specific builtins
        var defaultBcm = DefaultCostModel.defaultBuiltinCostModel();
        var parsedBcm = parsed.builtinCostModel();

        // DropList: LinearInX(116711, 1957) CPU + Const(4) mem
        var dropListCpu = parsedBcm.get(DefaultFun.DropList).cpu();
        assertEquals(defaultBcm.get(DefaultFun.DropList).cpu().apply(10), dropListCpu.apply(10));
        var dropListMem = parsedBcm.get(DefaultFun.DropList).mem();
        assertEquals(4, dropListMem.apply());

        // InsertCoin: LinearInU (4th arg) — test with 4 args
        var insertCoinCpu = parsedBcm.get(DefaultFun.InsertCoin).cpu();
        assertEquals(defaultBcm.get(DefaultFun.InsertCoin).cpu().apply(1, 1, 1, 10),
                insertCoinCpu.apply(1, 1, 1, 10));

        // UnionValue: WithInteractionInXAndY
        var unionCpu = parsedBcm.get(DefaultFun.UnionValue).cpu();
        assertEquals(defaultBcm.get(DefaultFun.UnionValue).cpu().apply(5, 10),
                unionCpu.apply(5, 10));

        // ValueContains: ConstAboveDiagonalLinear — above and below diagonal
        var vcCpu = parsedBcm.get(DefaultFun.ValueContains).cpu();
        assertEquals(defaultBcm.get(DefaultFun.ValueContains).cpu().apply(3, 10),
                vcCpu.apply(3, 10));  // above diagonal
        assertEquals(defaultBcm.get(DefaultFun.ValueContains).cpu().apply(10, 3),
                vcCpu.apply(10, 3));  // below diagonal

        // UnValueData: QuadraticInX
        var uvdCpu = parsedBcm.get(DefaultFun.UnValueData).cpu();
        assertEquals(defaultBcm.get(DefaultFun.UnValueData).cpu().apply(20),
                uvdCpu.apply(20));

        // ExpModInteger (moved from defaults-only to PV11 array)
        var expModCpu = parsedBcm.get(DefaultFun.ExpModInteger).cpu();
        assertEquals(defaultBcm.get(DefaultFun.ExpModInteger).cpu().apply(5, 10, 20),
                expModCpu.apply(5, 10, 20));
    }

    @Test
    void parse_pv11_allBuiltinCostsMatch() {
        // Comprehensive PV11 check: for every builtin with defaults, verify parsed matches
        long[] flat = CostModelParser.defaultToFlatArray(11);
        var parsed = CostModelParser.parse(flat, 11);

        var defaultBcm = DefaultCostModel.defaultBuiltinCostModel();
        var parsedBcm = parsed.builtinCostModel();

        long[] testSizes = {1, 5, 10, 20, 100};

        for (var fun : DefaultFun.values()) {
            var defaultPair = defaultBcm.get(fun);
            if (defaultPair == null) continue;
            // MultiIndexArray is JuLC-specific, not in PV11 flat array
            if (fun == DefaultFun.MultiIndexArray) continue;

            var parsedPair = parsedBcm.get(fun);
            assertNotNull(parsedPair, "Missing cost pair for " + fun);

            // Test with 4-arg sizes (covers LinearInU which needs 4th arg)
            for (long s1 : testSizes) {
                for (long s2 : testSizes) {
                    assertEquals(defaultPair.cpu().apply(s1, s2, s1, s2),
                            parsedPair.cpu().apply(s1, s2, s1, s2),
                            fun + " CPU mismatch at sizes " + s1 + "," + s2);
                    assertEquals(defaultPair.mem().apply(s1, s2, s1, s2),
                            parsedPair.mem().apply(s1, s2, s1, s2),
                            fun + " Mem mismatch at sizes " + s1 + "," + s2);
                }
            }
        }
    }

    @Test
    void parse_pv11_arrayRoundTrip() {
        // PV11 serialize→parse→serialize must be lossless
        long[] original = CostModelParser.defaultToFlatArray(11);
        var parsed = CostModelParser.parse(original, 11);
        long[] roundTripped = CostModelParser.toFlatArray(parsed.machineCosts(), parsed.builtinCostModel(), 11);

        assertEquals(original.length, roundTripped.length);
        for (int i = 0; i < original.length; i++) {
            assertEquals(original[i], roundTripped[i],
                    "PV11 round-trip mismatch at index " + i);
        }
    }

    @Test
    void parse_pv11_wrongArrayLength_throws() {
        // PV11 parse should reject arrays shorter than 350
        assertThrows(IllegalArgumentException.class, () ->
                CostModelParser.parse(new long[297], 11));
        assertThrows(IllegalArgumentException.class, () ->
                CostModelParser.parse(new long[349], 11));
    }

    @Test
    void parse_pv10_backwardCompat() {
        // PV10 arrays (297 params) still parse correctly at PV10
        long[] flat = CostModelParser.defaultToFlatArray();
        assertEquals(297, flat.length);

        var parsed = CostModelParser.parse(flat, 10);
        assertNotNull(parsed);
        assertEquals(DefaultCostModel.defaultMachineCosts(), parsed.machineCosts());

        // PV10 parse should NOT have PV11 builtins from parsing (they come from defaults)
        var bcm = parsed.builtinCostModel();
        // These are present because DefaultCostModel now includes them as defaults
        assertNotNull(bcm.get(DefaultFun.DropList), "DropList should be present from defaults");
    }

    @Test
    void parse_pv11_setCostModelParams_endToEnd() {
        // End-to-end: build PV11 array, set on provider, evaluate with PV11 builtins
        var provider = new JavaVmProvider();
        long[] flat = CostModelParser.defaultToFlatArray(11);
        provider.setCostModelParams(flat, PlutusLanguage.PLUTUS_V3, 11, 0);

        // Simple program that doesn't use PV11 builtins — just verify it works
        var program = UplcParser.parseProgram(
                "(program 1.0.0 [[(builtin addInteger) (con integer 3)] (con integer 4)])");
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertTrue(result.isSuccess());
        assertTrue(result.budgetConsumed().cpuSteps() > 0);
    }

    @Test
    void parse_defaultValues_matchesVmBudget() {
        // Parse default flat array, evaluate, verify same budget as without setCostModelParams
        var provider1 = new JavaVmProvider();
        var provider2 = new JavaVmProvider();

        long[] flat = CostModelParser.defaultToFlatArray();
        provider2.setCostModelParams(flat, PlutusLanguage.PLUTUS_V3, 10, 0);

        var program = UplcParser.parseProgram(
                "(program 1.0.0 [[(builtin addInteger) (con integer 3)] (con integer 4)])");

        var result1 = provider1.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        var result2 = provider2.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertTrue(result1.isSuccess());
        assertTrue(result2.isSuccess());
        assertEquals(result1.budgetConsumed().cpuSteps(), result2.budgetConsumed().cpuSteps(),
                "CPU budget should match with explicit default cost model");
        assertEquals(result1.budgetConsumed().memoryUnits(), result2.budgetConsumed().memoryUnits(),
                "Memory budget should match with explicit default cost model");
    }

    // === DefaultCostModel PV11 builtin tests ===

    @Test
    void defaultCostModel_pv11BuiltinsHaveNonNullCosts() {
        var bcm = DefaultCostModel.defaultBuiltinCostModel();
        DefaultFun[] pv11Builtins = {
                DefaultFun.DropList, DefaultFun.LengthOfArray, DefaultFun.ListToArray,
                DefaultFun.IndexArray, DefaultFun.Bls12_381_G1_multiScalarMul,
                DefaultFun.Bls12_381_G2_multiScalarMul, DefaultFun.InsertCoin,
                DefaultFun.LookupCoin, DefaultFun.UnionValue, DefaultFun.ValueContains,
                DefaultFun.ValueData, DefaultFun.UnValueData, DefaultFun.ScaleValue,
                DefaultFun.MultiIndexArray
        };
        for (var fun : pv11Builtins) {
            var pair = bcm.get(fun);
            assertNotNull(pair, "Missing cost pair for PV11 builtin: " + fun);
            assertNotNull(pair.cpu(), "Null CPU cost for " + fun);
            assertNotNull(pair.mem(), "Null mem cost for " + fun);
        }
    }

    @Test
    void pv11_customCostModel_affectsBudget() {
        long[] defaultFlat = CostModelParser.defaultToFlatArray(11);
        long[] custom = defaultFlat.clone();
        custom[302] += 1000; // DropList CPU intercept (index 302)

        var defaultProvider = new JavaVmProvider();
        var customProvider = new JavaVmProvider();
        customProvider.setCostModelParams(custom, PlutusLanguage.PLUTUS_V3, 11, 0);

        var program = UplcParser.parseProgram(
                "(program 1.0.0 [[(force (builtin dropList)) (con integer 0)] (con (list integer) [1,2,3])])");

        var defaultResult = defaultProvider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        var customResult = customProvider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertTrue(defaultResult.isSuccess(), "Default should succeed: " + defaultResult);
        assertTrue(customResult.isSuccess(), "Custom should succeed: " + customResult);
        assertTrue(customResult.budgetConsumed().cpuSteps() > defaultResult.budgetConsumed().cpuSteps(),
                "Custom CPU (" + customResult.budgetConsumed().cpuSteps()
                + ") should be > default CPU (" + defaultResult.budgetConsumed().cpuSteps() + ")");
    }

    @Test
    void parse_pv11_longerArray_accepted() {
        long[] flat = new long[400];
        long[] pv11Flat = CostModelParser.defaultToFlatArray(11);
        System.arraycopy(pv11Flat, 0, flat, 0, 350);
        var parsed = CostModelParser.parse(flat, 11);
        assertNotNull(parsed);
        assertEquals(DefaultCostModel.defaultMachineCosts(), parsed.machineCosts());
    }

    @Test
    void costFunctionShapes_pv11() {
        // Verify the new cost function types produce expected values

        // LinearInU: intercept + slope * sizes[3]
        var liu = new CostFunction.LinearInU(100, 5);
        assertEquals(100 + 5 * 20, liu.apply(1, 2, 3, 20));

        // QuadraticInX: c0 + c1*x + c2*x*x
        var qix = new CostFunction.QuadraticInX(10, 3, 2);
        assertEquals(10 + 3 * 5 + 2 * 25, qix.apply(5));

        // ConstAboveDiagonalLinear: constant when x < y, linear below
        var cadl = new CostFunction.ConstAboveDiagonalLinear(999, 100, 3, 7);
        assertEquals(999, cadl.apply(3, 10));  // above diagonal (x < y)
        assertEquals(100 + 3 * 10 + 7 * 3, cadl.apply(10, 3));  // below diagonal (x >= y)
        assertEquals(100 + 3 * 5 + 7 * 5, cadl.apply(5, 5));  // on diagonal (x == y)

        // WithInteractionInXAndY: c00 + c10*x + c01*y + c11*x*y
        var wi = new CostFunction.WithInteractionInXAndY(10, 3, 7, 2);
        assertEquals(10 + 3 * 5 + 7 * 4 + 2 * 5 * 4, wi.apply(5, 4));
    }
}
