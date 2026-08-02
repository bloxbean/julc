package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureProfile;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureRegistry;
import com.bloxbean.cardano.julc.vm.ProtocolVersion;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import com.bloxbean.cardano.julc.vm.java.builtins.UnsupportedBuiltinException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinCostCompletenessTest {

    private record Target(PlutusLanguage language, int protocol) {
        ProtocolFeatureProfile profile() {
            return ProtocolFeatureRegistry.resolve(new LedgerEvaluationTarget(
                    language, new ProtocolVersion(protocol, 0)));
        }
    }

    private static final List<Target> SUPPORTED_TARGETS = List.of(
            new Target(PlutusLanguage.PLUTUS_V1, 5),
            new Target(PlutusLanguage.PLUTUS_V1, 6),
            new Target(PlutusLanguage.PLUTUS_V1, 7),
            new Target(PlutusLanguage.PLUTUS_V1, 8),
            new Target(PlutusLanguage.PLUTUS_V1, 9),
            new Target(PlutusLanguage.PLUTUS_V1, 10),
            new Target(PlutusLanguage.PLUTUS_V1, 11),
            new Target(PlutusLanguage.PLUTUS_V2, 7),
            new Target(PlutusLanguage.PLUTUS_V2, 8),
            new Target(PlutusLanguage.PLUTUS_V2, 9),
            new Target(PlutusLanguage.PLUTUS_V2, 10),
            new Target(PlutusLanguage.PLUTUS_V2, 11),
            new Target(PlutusLanguage.PLUTUS_V3, 9),
            new Target(PlutusLanguage.PLUTUS_V3, 10),
            new Target(PlutusLanguage.PLUTUS_V3, 11));

    @Test
    void defaultModelsExactlyCoverEverySupportedProfile() {
        for (var target : SUPPORTED_TARGETS) {
            var profile = target.profile();
            var model = DefaultCostModel.defaultBuiltinCostModel(profile);

            assertDoesNotThrow(() -> model.validateCompleteFor(profile), target.toString());
            assertEquals(profile.availableBuiltins(), model.costedBuiltins(), target.toString());
        }
    }

    @Test
    void parsedModelsCoverEverySupportedSchema() {
        for (var target : SUPPORTED_TARGETS) {
            var profile = target.profile();
            long[] values = new long[profile.costModelSchema().parameterCount()];
            Arrays.fill(values, 1);

            var parsed = CostModelParser.parse(
                    values, target.language(), target.protocol(), 0);

            assertDoesNotThrow(
                    () -> new ConfiguredCostModel(parsed, profile.target(), profile),
                    target.toString());
            assertTrue(parsed.builtinCostModel().missingCostsFor(profile).isEmpty(),
                    target.toString());
        }
    }

    @Test
    void configuredModelRejectsAnAvailableBuiltinWithoutAPrice() {
        var profile = new Target(PlutusLanguage.PLUTUS_V2, 10).profile();
        var complete = DefaultCostModel.defaultBuiltinCostModel(profile);
        var incomplete = without(complete, DefaultFun.IntegerToByteString);
        var parsed = new CostModelParser.ParsedCostModel(
                DefaultCostModel.defaultMachineCosts(profile), incomplete);

        var error = assertThrows(IncompleteCostModelException.class,
                () -> new ConfiguredCostModel(parsed, profile.target(), profile));

        assertTrue(error.getMessage().contains(profile.target().toString()));
        assertTrue(error.getMessage().contains("IntegerToByteString"));
        assertEquals(Set.of(DefaultFun.IntegerToByteString),
                incomplete.missingCostsFor(profile));
    }

    @Test
    void costPairRequiresBothCpuAndMemoryPrices() {
        var one = new CostFunction.ConstantCost(1);

        assertThrows(IncompleteCostModelException.class,
                () -> new BuiltinCostModel.CostPair(null, one));
        assertThrows(IncompleteCostModelException.class,
                () -> new BuiltinCostModel.CostPair(one, null));
    }

    @Test
    void chargingBoundaryFailsClosedAndNeverReportsZeroCostSuccess() {
        var profile = new Target(PlutusLanguage.PLUTUS_V3, 10).profile();
        var model = without(
                DefaultCostModel.defaultBuiltinCostModel(profile), DefaultFun.AddInteger);
        var tracker = new CostTracker(
                DefaultCostModel.defaultMachineCosts(profile), model, profile, null);
        var args = List.<CekValue>of(
                new CekValue.VCon(Constant.integer(1)),
                new CekValue.VCon(Constant.integer(2)));

        var error = assertThrows(IncompleteCostModelException.class,
                () -> tracker.chargeBuiltin(DefaultFun.AddInteger, args));

        assertTrue(error.getMessage().contains("AddInteger"));
        assertEquals(ExBudget.ZERO, tracker.consumed());
    }

    @Test
    void unavailableBuiltinIsNotMistakenForAMissingPrice() {
        var profile = new Target(PlutusLanguage.PLUTUS_V3, 10).profile();
        var model = DefaultCostModel.defaultBuiltinCostModel(profile);

        assertFalse(profile.isBuiltinAvailable(DefaultFun.DropList));
        assertNull(model.get(DefaultFun.DropList));
        assertDoesNotThrow(() -> model.validateCompleteFor(profile));
        assertDoesNotThrow(() -> new ConfiguredCostModel(
                new CostModelParser.ParsedCostModel(
                        DefaultCostModel.defaultMachineCosts(profile), model),
                profile.target(), profile));

        var tracker = new CostTracker(
                DefaultCostModel.defaultMachineCosts(profile), model, profile, null);
        var error = assertThrows(UnsupportedBuiltinException.class,
                () -> tracker.chargeBuiltin(DefaultFun.DropList, List.of()));
        assertTrue(error.getMessage().contains("not available"));
        assertEquals(ExBudget.ZERO, tracker.consumed());
    }

    private static BuiltinCostModel without(BuiltinCostModel model, DefaultFun omitted) {
        var costs = new EnumMap<DefaultFun, BuiltinCostModel.CostPair>(DefaultFun.class);
        for (var fun : model.costedBuiltins()) {
            if (fun != omitted) {
                costs.put(fun, model.get(fun));
            }
        }
        return new BuiltinCostModel(costs);
    }
}
