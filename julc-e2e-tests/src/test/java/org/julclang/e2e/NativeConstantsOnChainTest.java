package org.julclang.e2e;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.Language;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-045/046/047: exact serialized artifacts must be accepted by the PV11 node. */
@Tag("native-constants-onchain")
class NativeConstantsOnChainTest extends E2ETestBase {
    @Override
    @BeforeAll
    void setUp() {
        HaskellScriptCost.requireConfigured();
        super.setUp();
    }

    @ParameterizedTest
    @MethodSource("org.julclang.e2e.NativeConstantFixtures#cases")
    void confirmedNativeConstantsMatchHaskell(NativeConstantFixtures.Kind kind, OptimizationLevel level) throws Exception {
        var params = new DefaultProtocolParamsSupplier(backendService.getEpochService()).getProtocolParams();
        assertEquals(11, params.getProtocolMajorVer(), "Release fixture is pinned to PV11");
        assertEquals(0, params.getProtocolMinorVer());
        assertArrayEquals(OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1.costModelParameters(),
                CostModelUtil.getCostModelFromProtocolParams(params, Language.PLUTUS_V3).orElseThrow().getCosts(),
                "Network cost-model drift: review the model and baselines, do not silently update goldens");
        var compiled = NativeConstantFixtures.compile(kind, level);
        var script = JulcScriptAdapter.fromProgram(compiled.program());
        var hash = JulcScriptAdapter.scriptHash(compiled.program());
        var address = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        var evaluator = new JulcTransactionEvaluator(new DefaultUtxoSupplier(backendService.getUtxoService()),
                new DefaultProtocolParamsSupplier(backendService.getEpochService()),
                new DefaultScriptSupplier(backendService.getScriptService()));

        for (var scenario : NativeConstantFixtures.scenarios(kind)) {
            var lock = quickTxBuilder.compose(new Tx()
                            .payToContract(address, Amount.ada(5), BigIntPlutusData.of(scenario.datum()))
                            .from(testAccount.baseAddress()))
                    .withSigner(SignerProviders.signerFrom(testAccount)).complete();
            assertTrue(lock.isSuccessful(), lock.toString());
            waitForConfirmation(lock.getValue());
            var input = exactUtxo(address, lock.getValue());
            var transaction = quickTxBuilder.compose(new ScriptTx()
                            .collectFrom(input, BigIntPlutusData.of(scenario.redeemer()))
                            .payToAddress(testAccount.baseAddress(), Amount.ada(4)).attachSpendingValidator(script))
                    .withTxEvaluator(evaluator).withSigner(SignerProviders.signerFrom(testAccount))
                    .feePayer(testAccount.baseAddress()).collateralPayer(testAccount.baseAddress()).buildAndSign();
            assertTrue(transaction.isValid());
            byte[] cbor = transaction.serialize(); // Only these original signed bytes may be submitted.
            var local = evaluator.evaluateTx(cbor, Set.of(input));
            var backend = backendService.getTransactionService().evaluateTx(cbor);
            assertTrue(local.isSuccessful(), local.getResponse());
            assertTrue(backend.isSuccessful(), backend.getResponse());
            assertEquals(1, local.getValue().size());
            assertEquals(1, backend.getValue().size());
            var budget = local.getValue().getFirst().getExUnits();
            assertEquals(budget, backend.getValue().getFirst().getExUnits());
            assertEquals(1, transaction.getWitnessSet().getRedeemers().size());
            assertEquals(budget, transaction.getWitnessSet().getRedeemers().getFirst().getExUnits());
            var haskell = HaskellScriptCost.evaluate(cbor);
            assertEquals(0, haskell.exitCode(), haskell.output());
            var costs = new ObjectMapper().readTree(haskell.output());
            assertTrue(costs.isArray(), haskell.output());
            assertEquals(1, costs.size());
            assertEquals(hash, costs.get(0).path("scriptHash").asText());
            assertEquals(budget.getSteps(), costs.get(0).path("executionUnits").path("steps").bigIntegerValue());
            assertEquals(budget.getMem(), costs.get(0).path("executionUnits").path("memory").bigIntegerValue());
            NativeConstantFixtures.assertGolden(kind, level, scenario, compiled,
                    budget.getSteps().longValueExact(), budget.getMem().longValueExact());

            if (scenario.equals(NativeConstantFixtures.scenarios(kind).getFirst())) {
                var witness = transaction.getWitnessSet().getRedeemers().getFirst();
                var original = witness.getData();
                try {
                    int index = 0;
                    for (var bad : NativeConstantFixtures.rejected(kind)) {
                        witness.setData(PlutusDataAdapter.toClientLib(bad.data()));
                        var rejected = transaction.serialize();
                        var rejectedLocal = evaluator.evaluateTx(rejected, Set.of(input));
                        var rejectedBackend = backendService.getTransactionService().evaluateTx(rejected);
                        var rejectedHaskell = HaskellScriptCost.evaluate(rejected);
                        assertFalse(rejectedLocal.isSuccessful());
                        assertTrue(rejectedLocal.getResponse().contains("Script evaluation failed"), rejectedLocal.getResponse());
                        assertTrue(rejectedLocal.getResponse().contains(bad.javaCause()), rejectedLocal.getResponse());
                        assertFalse(rejectedBackend.isSuccessful());
                        HaskellScriptCost.assertBackendFailure(rejectedBackend.getResponse(),
                                bad.javaCause().equals("Error term encountered") ? "Error evaluated" : bad.javaCause(), bad.haskellCause());
                        assertNotEquals(0, rejectedHaskell.exitCode());
                        assertTrue(rejectedHaskell.output().contains("Script evaluation error:"), rejectedHaskell.output());
                        assertTrue(rejectedHaskell.output().contains(bad.haskellCause()), rejectedHaskell.output());
                        System.out.printf("NATIVE_REJECTED %s %s case=%d java/backend/haskell%n", kind, level, index++);
                    }
                } finally {
                    witness.setData(original);
                }
            }
            var submitted = backendService.getTransactionService().submitTransaction(cbor);
            assertTrue(submitted.isSuccessful(), submitted.getResponse());
            waitForConfirmation(submitted.getValue());
            System.out.printf("NATIVE_CONFIRMED %s %s %s hash=%s cpu=%s mem=%s lock=%s spend=%s%n",
                    kind, level, scenario.name(), hash, budget.getSteps(), budget.getMem(), lock.getValue(), submitted.getValue());
        }
    }
}
