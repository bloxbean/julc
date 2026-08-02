package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import com.bloxbean.cardano.julc.vm.java.JavaVmProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.cost.CostFunction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden index tests against the V1/V2 ParamName enumerations at Plutus
 * f92b7d7d82622a26caf456a6be33859f697e2cfc (cardano-node 11.0.1).
 */
class V1V2CostModelParserTest {

    private record Schema(PlutusLanguage language, int protocol, int count) {
    }

    private static final List<Schema> SCHEMAS = List.of(
            new Schema(PlutusLanguage.PLUTUS_V1, 5, 166),
            new Schema(PlutusLanguage.PLUTUS_V1, 8, 166),
            new Schema(PlutusLanguage.PLUTUS_V1, 9, 166),
            new Schema(PlutusLanguage.PLUTUS_V1, 10, 166),
            new Schema(PlutusLanguage.PLUTUS_V1, 11, 332),
            new Schema(PlutusLanguage.PLUTUS_V2, 7, 175),
            new Schema(PlutusLanguage.PLUTUS_V2, 8, 175),
            new Schema(PlutusLanguage.PLUTUS_V2, 9, 175),
            new Schema(PlutusLanguage.PLUTUS_V2, 10, 185),
            new Schema(PlutusLanguage.PLUTUS_V2, 11, 332),
            new Schema(PlutusLanguage.PLUTUS_V3, 9, 251),
            new Schema(PlutusLanguage.PLUTUS_V3, 10, 297),
            new Schema(PlutusLanguage.PLUTUS_V3, 11, 350));

    @Test
    void selectsEveryPinnedSchemaByLanguageAndProtocol() {
        for (var schema : SCHEMAS) {
            assertEquals(schema.count(), CostModelParser.expectedParameterCount(
                    schema.language(), schema.protocol()), schema.toString());
        }

        assertThrows(IllegalArgumentException.class, () ->
                CostModelParser.expectedParameterCount(PlutusLanguage.PLUTUS_V1, 4));
        assertThrows(IllegalArgumentException.class, () ->
                CostModelParser.expectedParameterCount(PlutusLanguage.PLUTUS_V1, 12));
        assertThrows(IllegalArgumentException.class, () ->
                CostModelParser.expectedParameterCount(PlutusLanguage.PLUTUS_V2, 6));
        assertThrows(IllegalArgumentException.class, () ->
                CostModelParser.expectedParameterCount(PlutusLanguage.PLUTUS_V2, 12));
        assertThrows(IllegalArgumentException.class, () ->
                CostModelParser.expectedParameterCount(PlutusLanguage.PLUTUS_V3, 8));
        assertThrows(IllegalArgumentException.class, () ->
                CostModelParser.expectedParameterCount(PlutusLanguage.PLUTUS_V3, 12));
    }

    @Test
    void v1AndV2SchemasConsumeAndRoundTripEveryPosition() {
        for (var schema : SCHEMAS) {
            if (schema.language() == PlutusLanguage.PLUTUS_V3) {
                continue;
            }
            long[] original = indexedValues(schema.count());
            var parsed = CostModelParser.parse(
                    original, schema.language(), schema.protocol(), 0);
            long[] roundTrip = CostModelParser.toFlatArray(
                    parsed.machineCosts(), parsed.builtinCostModel(),
                    schema.language(), schema.protocol());

            assertArrayEquals(original, roundTrip, schema.toString());
        }
    }

    @Test
    void exactSchemaLengthsDoNotInferUnknownLayouts() {
        for (var schema : SCHEMAS) {
            assertThrows(IllegalArgumentException.class, () -> CostModelParser.parse(
                    new long[schema.count() - 1], schema.language(), schema.protocol(), 0),
                    "short " + schema);
            assertThrows(IllegalArgumentException.class, () -> CostModelParser.parse(
                    new long[schema.count() + 1], schema.language(), schema.protocol(), 0),
                    "long " + schema);
        }
    }

    @Test
    void v1Pv11LandmarksMatchPinnedParamNameOrder() {
        long[] values = indexedValues(CostModelParser.V1_PV11_PARAM_COUNT);
        var parsed = CostModelParser.parse(
                values, PlutusLanguage.PLUTUS_V1, 11, 0);
        var costs = parsed.builtinCostModel();

        assertEquals(new MaxSize(at(0), at(1)),
                costs.get(DefaultFun.AddInteger).cpu());
        assertEquals(new AboveAndBelowDiagonal(
                        at(49), new MultipliedSizes(at(50), at(51))),
                costs.get(DefaultFun.DivideInteger).cpu());
        assertEquals(new SubtractedSizes(at(52), at(54), at(53)),
                costs.get(DefaultFun.DivideInteger).mem());
        assertEquals(new LinearInX(at(166), at(167)),
                costs.get(DefaultFun.SerialiseData).cpu());
        assertEquals(at(175), parsed.machineCosts().constrCpu());
        assertEquals(at(178), parsed.machineCosts().caseMem());
        assertEquals(new ConstantCost(at(179)),
                costs.get(DefaultFun.Bls12_381_G1_add).cpu());
        assertEquals(new QuadraticInZ(at(223), at(224), at(225)),
                costs.get(DefaultFun.IntegerToByteString).cpu());
        assertEquals(new LinearInYAndZ(at(233), at(234), at(235)),
                costs.get(DefaultFun.AndByteString).cpu());
        assertEquals(new ExpModCost(at(279), at(280), at(281)),
                costs.get(DefaultFun.ExpModInteger).cpu());
        assertEquals(new LinearInY(at(328), at(329)),
                costs.get(DefaultFun.ScaleValue).cpu());
        assertEquals(new LinearInY(at(330), at(331)),
                costs.get(DefaultFun.ScaleValue).mem());
    }

    @Test
    void legacyParameterShapesSelectSemanticsA_B_AndD() {
        var semanticsA = CostModelParser.parse(
                indexedValues(CostModelParser.V1_PARAM_COUNT),
                PlutusLanguage.PLUTUS_V1, 8, 0).builtinCostModel();
        var semanticsB = CostModelParser.parse(
                indexedValues(CostModelParser.V1_PARAM_COUNT),
                PlutusLanguage.PLUTUS_V1, 10, 0).builtinCostModel();
        var semanticsD = CostModelParser.parse(
                indexedValues(CostModelParser.V1_PV11_PARAM_COUNT),
                PlutusLanguage.PLUTUS_V1, 11, 0).builtinCostModel();

        assertEquals(new AddedSizes(at(115), at(116)),
                semanticsA.get(DefaultFun.MultiplyInteger).cpu());
        assertEquals(new MultipliedSizes(at(115), at(116)),
                semanticsB.get(DefaultFun.MultiplyInteger).cpu());
        assertEquals(new LinearInZ(at(163), at(164)),
                semanticsA.get(DefaultFun.VerifyEd25519Signature).cpu());
        assertEquals(new LinearInY(at(163), at(164)),
                semanticsB.get(DefaultFun.VerifyEd25519Signature).cpu());

        assertEquals(new AboveAndBelowDiagonal(
                        at(49), new MultipliedSizes(at(50), at(51))),
                semanticsD.get(DefaultFun.DivideInteger).cpu());
        assertEquals(new AboveAndBelowDiagonal(
                        at(109), new MultipliedSizes(at(110), at(111))),
                semanticsD.get(DefaultFun.ModInteger).cpu());
        assertEquals(new LinearInY2(at(112), at(114), at(113)),
                semanticsD.get(DefaultFun.ModInteger).mem());
        assertInstanceOf(ConstAboveDiagonal.class,
                semanticsD.get(DefaultFun.QuotientInteger).cpu());
        assertEquals(new LinearInY2(at(130), at(132), at(131)),
                semanticsD.get(DefaultFun.RemainderInteger).mem());
    }

    @Test
    void pinnedPv11VariantDGoldenVectorsRoundTripInHaskellOrder() throws IOException {
        assertPinnedGoldenRoundTrip(
                "/cost-model/f92b7d7d8/plutus-v1-pv11-D.json",
                PlutusLanguage.PLUTUS_V1);
        assertPinnedGoldenRoundTrip(
                "/cost-model/f92b7d7d8/plutus-v2-pv11-D.json",
                PlutusLanguage.PLUTUS_V2);
    }

    @Test
    void v2Pv10AndPv11LandmarksMatchPinnedParamNameOrder() {
        long[] pv10Values = indexedValues(CostModelParser.V2_PV10_PARAM_COUNT);
        var pv10 = CostModelParser.parse(
                pv10Values, PlutusLanguage.PLUTUS_V2, 10, 0);
        assertEquals(new LinearInX(at(133), at(134)),
                pv10.builtinCostModel().get(DefaultFun.SerialiseData).cpu());
        assertEquals(new QuadraticInZ(at(175), at(176), at(177)),
                pv10.builtinCostModel().get(DefaultFun.IntegerToByteString).cpu());
        assertEquals(new QuadraticInY(at(180), at(181), at(182)),
                pv10.builtinCostModel().get(DefaultFun.ByteStringToInteger).cpu());

        long[] pv11Values = indexedValues(CostModelParser.V2_PV11_PARAM_COUNT);
        var pv11 = CostModelParser.parse(
                pv11Values, PlutusLanguage.PLUTUS_V2, 11, 0);
        assertEquals(at(185), pv11.machineCosts().constrCpu());
        assertEquals(at(188), pv11.machineCosts().caseMem());
        assertEquals(new ConstantCost(at(189)),
                pv11.builtinCostModel().get(DefaultFun.Bls12_381_G1_add).cpu());
        assertEquals(new LinearInYAndZ(at(233), at(234), at(235)),
                pv11.builtinCostModel().get(DefaultFun.AndByteString).cpu());
        assertEquals(new ExpModCost(at(279), at(280), at(281)),
                pv11.builtinCostModel().get(DefaultFun.ExpModInteger).cpu());
    }

    @Test
    void parsedModelsContainOnlyCoefficientsSuppliedByTheirSchema() {
        var v1 = CostModelParser.parse(
                indexedValues(166), PlutusLanguage.PLUTUS_V1, 10, 0)
                .builtinCostModel();
        var v2Pv10 = CostModelParser.parse(
                indexedValues(185), PlutusLanguage.PLUTUS_V2, 10, 0)
                .builtinCostModel();
        var v1Pv11 = CostModelParser.parse(
                indexedValues(332), PlutusLanguage.PLUTUS_V1, 11, 0)
                .builtinCostModel();
        var v2Pv11 = CostModelParser.parse(
                indexedValues(332), PlutusLanguage.PLUTUS_V2, 11, 0)
                .builtinCostModel();

        for (var fun : DefaultFun.values()) {
            assertEquals(fun.flatCode() <= 50, v1.get(fun) != null,
                    "V1/PV10 " + fun);
            assertEquals(fun.flatCode() <= 53
                            || fun == DefaultFun.IntegerToByteString
                            || fun == DefaultFun.ByteStringToInteger,
                    v2Pv10.get(fun) != null, "V2/PV10 " + fun);
            assertEquals(fun.flatCode() <= 100, v1Pv11.get(fun) != null,
                    "V1/PV11 " + fun);
            assertEquals(fun.flatCode() <= 100, v2Pv11.get(fun) != null,
                    "V2/PV11 " + fun);
        }
    }

    @Test
    void nodeProvidedV1AndV2BuiltinCoefficientsChangeEvaluatedBudgets() {
        assertAddIntegerInterceptChangesBudget(PlutusLanguage.PLUTUS_V1, 10);
        assertAddIntegerInterceptChangesBudget(PlutusLanguage.PLUTUS_V2, 10);
    }

    @Test
    void v2Pv10Batch4bCoefficientsReachTheCostingBoundary() {
        long[] baseline = indexedValues(CostModelParser.V2_PV10_PARAM_COUNT);
        long[] changed = baseline.clone();
        changed[175] += 10_000;

        var baseModel = CostModelParser.parse(
                baseline, PlutusLanguage.PLUTUS_V2, 10, 0);
        var changedModel = CostModelParser.parse(
                changed, PlutusLanguage.PLUTUS_V2, 10, 0);
        var args = List.<CekValue>of(
                new CekValue.VCon(Constant.bool(true)),
                new CekValue.VCon(Constant.integer(0)),
                new CekValue.VCon(Constant.integer(42)));

        var baseTracker = new CostTracker(
                baseModel.machineCosts(), baseModel.builtinCostModel(), null);
        var changedTracker = new CostTracker(
                changedModel.machineCosts(), changedModel.builtinCostModel(), null);
        baseTracker.chargeBuiltin(DefaultFun.IntegerToByteString, args);
        changedTracker.chargeBuiltin(DefaultFun.IntegerToByteString, args);

        assertEquals(10_000,
                changedTracker.cpuConsumed() - baseTracker.cpuConsumed());
        assertEquals(baseTracker.memConsumed(), changedTracker.memConsumed());
    }

    private static void assertAddIntegerInterceptChangesBudget(
            PlutusLanguage language, int protocol) {
        var defaults = completeV1V2Defaults(language);
        var machine = DefaultCostModel.defaultMachineCosts(language);
        long[] baseline = CostModelParser.toFlatArray(
                machine, defaults, language, protocol);
        long[] changed = baseline.clone();
        changed[0] += 12_345;

        var baselineProvider = new JavaVmProvider();
        baselineProvider.setCostModelParams(baseline, language, protocol, 0);
        var changedProvider = new JavaVmProvider();
        changedProvider.setCostModelParams(changed, language, protocol, 0);

        Term addition = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger),
                        Term.const_(Constant.integer(1))),
                Term.const_(Constant.integer(2)));
        Program program = language == PlutusLanguage.PLUTUS_V1
                ? Program.plutusV1(addition)
                : Program.plutusV2(addition);
        EvalResult baselineResult = baselineProvider.evaluate(program, language, null);
        EvalResult changedResult = changedProvider.evaluate(program, language, null);

        assertInstanceOf(EvalResult.Success.class, baselineResult);
        assertInstanceOf(EvalResult.Success.class, changedResult);
        assertEquals(12_345,
                changedResult.budgetConsumed().cpuSteps()
                        - baselineResult.budgetConsumed().cpuSteps());
        assertEquals(baselineResult.budgetConsumed().memoryUnits(),
                changedResult.budgetConsumed().memoryUnits());
    }

    private static BuiltinCostModel completeV1V2Defaults(PlutusLanguage language) {
        var full = DefaultCostModel.defaultBuiltinCostModel();
        var legacy = DefaultCostModel.defaultBuiltinCostModel(language);
        var costs = new EnumMap<DefaultFun, BuiltinCostModel.CostPair>(DefaultFun.class);
        for (var fun : DefaultFun.values()) {
            var cost = legacy.get(fun);
            if (cost == null) {
                cost = full.get(fun);
            }
            if (cost != null) {
                costs.put(fun, cost);
            }
        }
        return new BuiltinCostModel(costs);
    }

    private static void assertPinnedGoldenRoundTrip(
            String resource, PlutusLanguage language) throws IOException {
        long[] values = readFlatVector(resource);
        assertEquals(332, values.length);

        var parsed = CostModelParser.parse(values, language, 11, 0);
        assertArrayEquals(values, CostModelParser.toFlatArray(
                parsed.machineCosts(), parsed.builtinCostModel(), language, 11));
        assertEquals(16_000, parsed.machineCosts().constrCpu());
        assertEquals(100, parsed.machineCosts().caseMem());

        // Variant D runs multiplied_sizes after ordering both operands and
        // deliberately ignores the retained constant parameter.
        assertEquals(228_465 + 122L * 3 * 7,
                parsed.builtinCostModel().get(DefaultFun.DivideInteger)
                        .cpu().apply(3, 7));
        assertEquals(7,
                parsed.builtinCostModel().get(DefaultFun.ModInteger)
                        .mem().apply(99, 7));
    }

    private static long[] readFlatVector(String resource) throws IOException {
        try (var stream = V1V2CostModelParserTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "Missing golden resource " + resource);
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            String body = json.substring(1, json.length() - 1);
            if (body.isBlank()) {
                return new long[0];
            }
            String[] entries = body.split(",");
            long[] values = new long[entries.length];
            for (int i = 0; i < entries.length; i++) {
                values[i] = Long.parseLong(entries[i].trim());
            }
            return values;
        }
    }

    private static long[] indexedValues(int count) {
        long[] values = new long[count];
        for (int i = 0; i < values.length; i++) {
            values[i] = at(i);
        }
        return values;
    }

    private static long at(int index) {
        return 1_000L + index;
    }
}
