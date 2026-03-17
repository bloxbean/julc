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

            // Test with 3-arg sizes (covers all cost function arities)
            for (long s1 : testSizes) {
                for (long s2 : testSizes) {
                    for (long s3 : testSizes) {
                        assertEquals(defaultPair.cpu().apply(s1, s2, s3), parsedPair.cpu().apply(s1, s2, s3),
                                fun + " CPU mismatch at sizes " + s1 + "," + s2 + "," + s3);
                        assertEquals(defaultPair.mem().apply(s1, s2, s3), parsedPair.mem().apply(s1, s2, s3),
                                fun + " Mem mismatch at sizes " + s1 + "," + s2 + "," + s3);
                    }
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
        javaProvider.setCostModelParams(doubled, 10);
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
        // PV11 arrays (350 params) should parse without error, using first 297
        long[] flat = new long[350];
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
    void parse_defaultValues_matchesVmBudget() {
        // Parse default flat array, evaluate, verify same budget as without setCostModelParams
        var provider1 = new JavaVmProvider();
        var provider2 = new JavaVmProvider();

        long[] flat = CostModelParser.defaultToFlatArray();
        provider2.setCostModelParams(flat, 10);

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
}
