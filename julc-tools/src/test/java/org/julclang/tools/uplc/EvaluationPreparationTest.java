package org.julclang.tools.uplc;

import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.UplcModels.ScriptInput;
import org.julclang.tools.model.VmModels.CostModel;
import org.julclang.tools.model.VmModels.Request;
import org.julclang.tools.model.VmModels.Target;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.OptimizationCostProfiles;
import org.julclang.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationPreparationTest {
    private static final ScriptInput IDENTITY = new ScriptInput("(program 1.1.0 (lam x x))", null, null, null);

    @Test
    void rawArgumentsAndDebugReplayHaveIdenticalBudgetsAndValues() {
        var prepared = EvaluationPreparation.raw(new Request(IDENTITY,
                List.of(new DataInput("json", "{\"constructor\":18446744073709551615,\"fields\":[]}")),
                null, null, null, null));
        var result = prepared.provider().evaluateWithArgs(prepared.decoded().program(), prepared.target(),
                prepared.args(), prepared.budget(), EvalOptions.DEFAULT);
        var tools = new UplcToolsService();
        var timeline = tools.open(prepared).body().timeline();
        var end = tools.act("goto", timeline.totalSteps(), null).body().snapshot();
        assertTrue(result.isSuccess());
        assertEquals(result.budgetConsumed().cpuSteps(), end.cpu());
        assertEquals(result.budgetConsumed().memoryUnits(), end.mem());
        assertEquals(result.traces(), end.traces());
        assertTrue(end.value().contains("18446744073709551615"));
        assertEquals(0, tools.act("goto", 0L, null).body().snapshot().step());
        assertEquals(end, tools.act("goto", timeline.totalSteps(), null).body().snapshot());
    }

    @Test
    void legacyLanguageAndProtocolDefaultsRemainIndependent() {
        var v2 = new ScriptInput("(program 1.0.0 (lam x x))", null, "V2", null);
        var prepared = EvaluationPreparation.script(v2, null, null, null, null);
        assertEquals(PlutusLanguage.PLUTUS_V2, prepared.target().ledgerLanguage());
        assertEquals(11, prepared.target().protocolVersion().major());
        assertEquals(10, EvaluationPreparation.script(v2, new Target(null, 10), null, null, null)
                .target().protocolVersion().major());
    }

    @Test
    void costModelsCannotLeakIntoOtherRequestsOrDebugReplays() {
        var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
        var custom = Arrays.stream(profile.costModelParameters()).boxed().toList();
        var request = new Request(IDENTITY, List.of(new DataInput("auto", "42")), null,
                new CostModel(null, new Target("V3", 11), custom), null, null);
        var prepared = EvaluationPreparation.raw(request);
        var tools = new UplcToolsService();
        var initial = tools.open(prepared).body().timeline();
        var other = EvaluationPreparation.script(IDENTITY, null, null, null, null);
        assertNotSame(prepared.provider(), other.provider());
        assertEquals(initial, tools.act("timeline", 0L, null).body().timeline());
        assertTrue(other.costModelId().startsWith("default:"));
        assertThrows(IllegalArgumentException.class, () -> EvaluationPreparation.script(IDENTITY,
                new Target("V2", 11), new CostModel(profile.profileId(), null, null), null, null));
    }

    @Test
    void failureAndBudgetExhaustionAgreeWithStepping() {
        for (String script : List.of("(program 1.1.0 (error))", "(program 1.1.0 (con unit ()))")) {
            for (long budget : List.of(0L, 1000000L)) {
                var prepared = EvaluationPreparation.script(new ScriptInput(script, null, "V3", null),
                        null, null, budget, budget);
                var tools = new UplcToolsService();
                var result = tools.evaluatePrepared(prepared).body();
                var timeline = tools.open(prepared).body().timeline();
                var end = tools.act("goto", timeline.totalSteps(), null).body().snapshot();
                assertEquals(result.status(), end.status());
                assertEquals(result.cpu(), end.cpu());
                assertEquals(result.mem(), end.mem());
                // Snapshot.span is the current machine location, not exclusively
                // the failed term: startup exhaustion has no failed term but still
                // has a current term. Compare actual VM results independently.
                if (result.failedSpan() != null) assertEquals(result.failedSpan(), end.span());
                var direct = prepared.provider().evaluateWithArgs(prepared.decoded().program(), prepared.target(),
                        prepared.args(), prepared.budget(), EvalOptions.DEFAULT);
                var stepping = prepared.provider().startStepping(prepared.decoded().program(), prepared.target(),
                        prepared.args(), prepared.budget(), EvalOptions.DEFAULT);
                while (!stepping.isFinished()) stepping.step();
                assertEquals(direct, stepping.result());
            }
        }
    }
}
