package com.bloxbean.cardano.julc.e2e;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.Language;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.eval.JulcTransactionEvaluator;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.OptimizationLevel;
import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.vm.OptimizationCostProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in ADR-034 release gate: :julc-e2e-tests:listCaseOnChainTest -Pe2e. */
@Tag("list-case-onchain")
class ListCaseOnChainTest extends E2ETestBase {
    private static final String SOURCE = """
            import java.math.BigInteger;
            import com.bloxbean.cardano.julc.core.types.JulcList;
            @SpendingValidator
            class ListCaseGate {
                @Entrypoint
                static boolean validate(BigInteger datum, JulcList<BigInteger> redeemer, PlutusData ctx) {
                    BigInteger sum = BigInteger.ZERO;
                    for (BigInteger item : redeemer) {
                        sum = sum.add(item);
                        if (item.equals(BigInteger.ZERO)) { break; }
                    }
                    return sum.equals(datum);
                }
            }
            """;

    private record Scenario(String name, long expected, long[] items) {}
    private record CliResult(int exitCode, String output) {}

    @Override
    @BeforeAll
    void setUp() {
        assertNotNull(System.getenv("JULC_E2E_CARDANO_CONTAINER"),
                "Set JULC_E2E_CARDANO_CONTAINER to the running developer DevKit container");
        super.setUp();
    }

    @ParameterizedTest
    @EnumSource(value = OptimizationLevel.class, names = {"BASELINE", "PV11_SAFE"})
    void confirmedTraversalMatchesNodeBudgets(OptimizationLevel level) throws Exception {
        var params = new DefaultProtocolParamsSupplier(backendService.getEpochService()).getProtocolParams();
        assertEquals(11, params.getProtocolMajorVer());
        assertEquals(0, params.getProtocolMinorVer());
        assertArrayEquals(OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11.costModelParameters(),
                CostModelUtil.getCostModelFromProtocolParams(params, Language.PLUTUS_V3).orElseThrow().getCosts());
        var options = new CompilerOptions().setOptimizationLevel(level);
        var compiled = new JulcCompiler(stdlib, options).compile(SOURCE);
        assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
        boolean safe = level == OptimizationLevel.PV11_SAFE;
        assertEquals(safe, compiled.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_LIST_RULE));
        byte[] flat = UplcFlatEncoder.encodeProgram(compiled.program());
        var decoded = UplcFlatDecoder.decodeProgram(flat);
        assertArrayEquals(flat, UplcFlatEncoder.encodeProgram(decoded));
        assertEquals(safe ? 1 : 0, listCaseSites(decoded.term()));
        if (safe) {
            var defaults = new JulcCompiler(stdlib).compile(SOURCE);
            assertFalse(defaults.hasErrors(), defaults.diagnostics().toString());
            assertArrayEquals(flat, UplcFlatEncoder.encodeProgram(defaults.program()));
        }
        var script = JulcScriptAdapter.fromProgram(decoded);
        String address = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.printf("LIST_CASE_ARTIFACT %s bytes=%d hash=%s sites=%d%n",
                level, flat.length, JulcScriptAdapter.scriptHash(decoded), listCaseSites(decoded.term()));

        for (var scenario : List.of(new Scenario("empty", 0, new long[]{}),
                new Scenario("singleton", 7, new long[]{7}),
                new Scenario("multiple", 9, new long[]{2, 3, 4}),
                new Scenario("break", 2, new long[]{2, 0, 99}))) {
            var lock = quickTxBuilder.compose(new Tx()
                            .payToContract(address, Amount.ada(5), BigIntPlutusData.of(scenario.expected()))
                            .from(testAccount.baseAddress()))
                    .withSigner(SignerProviders.signerFrom(testAccount)).complete();
            assertTrue(lock.isSuccessful(), lock.toString());
            waitForConfirmation(lock.getValue());
            Utxo input = exactUtxo(address, lock.getValue());
            var redeemer = ListPlutusData.of(Arrays.stream(scenario.items())
                    .mapToObj(BigIntPlutusData::of).toArray(PlutusData[]::new));
            var evaluator = new JulcTransactionEvaluator(
                    new DefaultUtxoSupplier(backendService.getUtxoService()),
                    new DefaultProtocolParamsSupplier(backendService.getEpochService()),
                    new DefaultScriptSupplier(backendService.getScriptService()));
            var transaction = quickTxBuilder.compose(new ScriptTx()
                            .collectFrom(input, redeemer)
                            .payToAddress(testAccount.baseAddress(), Amount.ada(4))
                            .attachSpendingValidator(script))
                    .withTxEvaluator(evaluator)
                    .withSigner(SignerProviders.signerFrom(testAccount))
                    .feePayer(testAccount.baseAddress()).collateralPayer(testAccount.baseAddress())
                    .buildAndSign();
            assertTrue(transaction.isValid(), "Evidence must confirm a phase-2-valid transaction");
            byte[] cbor = transaction.serialize();
            var local = evaluator.evaluateTx(cbor, Set.of(input));
            var backendEvaluation = backendService.getTransactionService().evaluateTx(cbor);
            assertTrue(local.isSuccessful(), local.getResponse());
            assertTrue(backendEvaluation.isSuccessful(), backendEvaluation.getResponse());
            assertEquals(1, local.getValue().size());
            assertEquals(1, backendEvaluation.getValue().size());
            var budget = local.getValue().getFirst().getExUnits();
            assertEquals(budget, backendEvaluation.getValue().getFirst().getExUnits());
            assertEquals(1, transaction.getWitnessSet().getRedeemers().size());
            assertEquals(budget, transaction.getWitnessSet().getRedeemers().getFirst().getExUnits());
            if (scenario.name().equals("singleton")) {
                // Evaluate invalid variants only. The original signed bytes above are
                // immutable and are the only bytes submitted after these probes.
                var witness = transaction.getWitnessSet().getRedeemers().getFirst();
                var original = witness.getData();
                List<PlutusData> invalid = List.of(
                        ListPlutusData.of(BigIntPlutusData.of(8)),
                        BigIntPlutusData.of(7),
                        ListPlutusData.of(BytesPlutusData.of(new byte[]{0})),
                        ListPlutusData.of(BigIntPlutusData.of(0), BytesPlutusData.of(new byte[]{0})));
                try {
                    for (int i = 0; i < invalid.size(); i++) {
                        witness.setData(invalid.get(i));
                        byte[] rejectedBytes = transaction.serialize();
                        var rejectedLocal = evaluator.evaluateTx(rejectedBytes, Set.of(input));
                        var rejectedBackend = backendService.getTransactionService().evaluateTx(rejectedBytes);
                        assertFalse(rejectedLocal.isSuccessful(), "Java accepted invalid case " + i);
                        assertFalse(rejectedBackend.isSuccessful(), "Backend accepted invalid case " + i);
                        String failure = i == 0 ? "Error term encountered" : i == 1 ? "UnListData" : "UnIData";
                        assertTrue(rejectedLocal.getResponse().contains("Script evaluation failed"), rejectedLocal.getResponse());
                        assertTrue(rejectedLocal.getResponse().contains(failure), rejectedLocal.getResponse());
                        assertTrue(rejectedBackend.getResponse().contains("EvaluationFailure"), rejectedBackend.getResponse());
                        assertTrue(rejectedBackend.getResponse().contains(i == 0 ? "Error evaluated" : failure), rejectedBackend.getResponse());
                        var rejectedHaskell = haskellCost(rejectedBytes);
                        assertNotEquals(0, rejectedHaskell.exitCode(), rejectedHaskell.output());
                        assertTrue(rejectedHaskell.output().contains("Script evaluation error:"), rejectedHaskell.output());
                        String cause = i == 0 ? "Caused by: (error)"
                                : i == 1 ? "Caused by: [ (builtin unListData)" : "Caused by: [ (builtin unIData)";
                        assertTrue(rejectedHaskell.output().contains(cause), rejectedHaskell.output());
                        System.out.printf("LIST_CASE_REJECTED %s case=%d java/backend/haskell=%s%n", level, i, failure);
                    }
                } finally {
                    witness.setData(original);
                }
            }
            var haskell = haskellCost(cbor);
            assertEquals(0, haskell.exitCode(), haskell.output());
            var costs = new ObjectMapper().readTree(haskell.output());
            assertTrue(costs.isArray(), haskell.output());
            assertEquals(1, costs.size(), haskell.output());
            assertEquals(JulcScriptAdapter.scriptHash(decoded), costs.get(0).path("scriptHash").asText());
            var units = costs.get(0).path("executionUnits");
            assertEquals(budget.getSteps(), units.path("steps").bigIntegerValue());
            assertEquals(budget.getMem(), units.path("memory").bigIntegerValue());
            System.out.printf("LIST_CASE_HASKELL_MATCH %s %s cpu=%s mem=%s%n",
                    level, scenario.name(), budget.getSteps(), budget.getMem());
            var submitted = backendService.getTransactionService().submitTransaction(cbor);
            assertTrue(submitted.isSuccessful(), submitted.getResponse());
            waitForConfirmation(submitted.getValue());
            System.out.printf("LIST_CASE_CONFIRMED %s %s cpu=%s mem=%s lock=%s spend=%s%n",
                    level, scenario.name(), budget.getSteps(), budget.getMem(), lock.getValue(), submitted.getValue());
        }
    }

    private static CliResult haskellCost(byte[] transaction) throws Exception {
        String container = System.getenv("JULC_E2E_CARDANO_CONTAINER");
        assertNotNull(container, "Set JULC_E2E_CARDANO_CONTAINER to the running developer DevKit container");
        var outputFile = Files.createTempFile("julc-list-case-cli-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder("docker", "exec", "-i", container,
                    "/app/cardano-bin/cardano-cli", "conway", "transaction", "calculate-plutus-script-cost", "online",
                    "--testnet-magic", "42", "--socket-path", "/clusters/nodes/default/node/node.sock",
                    "--tx-file", "/dev/stdin").redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
            String envelope = "{\"type\":\"Tx ConwayEra\",\"description\":\"ADR-034 test\",\"cborHex\":\""
                    + HexFormat.of().formatHex(transaction) + "\"}";
            try (var input = process.getOutputStream()) {
                input.write(envelope.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Haskell evaluation timed out");
            return new CliResult(process.exitValue(), Files.readString(outputFile));
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(outputFile);
        }
    }

    private Utxo exactUtxo(String address, String hash) throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            var result = backendService.getUtxoService().getUtxos(address, 100, 1);
            assertTrue(result.isSuccessful(), result.getResponse());
            var match = result.getValue().stream().filter(u -> hash.equals(u.getTxHash())).findFirst();
            if (match.isPresent()) return match.get();
            Thread.sleep(1000);
        }
        throw new AssertionError("Missing exact script UTXO for " + hash);
    }

    private static int listCaseSites(Term term) {
        int here = 0;
        if (term instanceof Term.Case guard && guard.branches().size() == 2
                && guard.scrutinee() instanceof Term.Apply test
                && test.function() instanceof Term.Force force
                && force.term() instanceof Term.Builtin builtin && builtin.fun() == DefaultFun.NullList
                && guard.branches().getFirst() instanceof Term.Case match
                && match.scrutinee().equals(test.argument()) && match.branches().size() == 2
                && match.branches().getFirst() instanceof Term.Lam head && head.body() instanceof Term.Lam
                && match.branches().get(1) instanceof Term.Error) {
            here = 1;
        }
        return here + switch (term) {
            case Term.Apply a -> listCaseSites(a.function()) + listCaseSites(a.argument());
            case Term.Lam l -> listCaseSites(l.body());
            case Term.Force f -> listCaseSites(f.term());
            case Term.Delay d -> listCaseSites(d.term());
            case Term.Case c -> listCaseSites(c.scrutinee()) + c.branches().stream().mapToInt(ListCaseOnChainTest::listCaseSites).sum();
            case Term.Constr c -> c.fields().stream().mapToInt(ListCaseOnChainTest::listCaseSites).sum();
            default -> 0;
        };
    }
}
