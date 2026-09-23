package org.julclang.e2e;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("onchain-harness")
class NativeConstantsRegressionTest {
    @ParameterizedTest
    @MethodSource("org.julclang.e2e.NativeConstantFixtures#cases")
    void semanticAndArtifactRegression(NativeConstantFixtures.Kind kind, OptimizationLevel level) throws Exception {
        var compiled = NativeConstantFixtures.compile(kind, level);
        var vm = JulcVm.create("Java");
        vm.setCostModelParams(OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1.costModelParameters(),
                compiled.target().ledgerTarget());
        for (var scenario : NativeConstantFixtures.scenarios(kind)) {
            var context = context(scenario.datum(), PlutusData.integer(scenario.redeemer()));
            var result = vm.evaluateWithArgs(compiled.program(), compiled.target().ledgerTarget(),
                    List.of(context), null, EvalOptions.DEFAULT);
            assertInstanceOf(EvalResult.Success.class, result, result.toString());
            NativeConstantFixtures.assertGolden(kind, level, scenario, compiled,
                    result.budgetConsumed().cpuSteps(), result.budgetConsumed().memoryUnits());
            System.out.printf("NATIVE_GOLDEN %s.%s.%s=%s,%d,%d,%d%n", kind, level, scenario.name(),
                    JulcScriptAdapter.scriptHash(compiled.program()), UplcFlatEncoder.encodeProgram(compiled.program()).length,
                    result.budgetConsumed().cpuSteps(), result.budgetConsumed().memoryUnits());
        }
        long datum = NativeConstantFixtures.scenarios(kind).getFirst().datum();
        for (var bad : NativeConstantFixtures.rejected(kind)) {
            var failure = assertInstanceOf(EvalResult.Failure.class, vm.evaluateWithArgs(compiled.program(), compiled.target().ledgerTarget(),
                    List.of(context(datum, bad.data())), null, EvalOptions.DEFAULT));
            assertTrue(failure.error().contains(bad.javaCause()), failure.error());
        }
    }

    private static PlutusData context(long datum, PlutusData redeemer) {
        return ScriptContextTestBuilder.spending(new TxOutRef(TxId.of(new byte[32]), BigInteger.ZERO),
                PlutusData.integer(datum)).redeemer(redeemer).buildPlutusData();
    }
}
