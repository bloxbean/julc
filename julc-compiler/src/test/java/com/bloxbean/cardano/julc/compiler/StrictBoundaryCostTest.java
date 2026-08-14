package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StrictBoundaryCostTest {
    private static final long CARDANO_MAX_TX_CPU = 10_000_000_000L;
    private static final long CARDANO_MAX_TX_MEMORY = 14_000_000L;
    // Immutable pre-ADR-015 measurements recorded in
    // verification/strict-boundaries/measurements.json. The current compiler
    // deliberately cannot regenerate permissive legacy artifacts.
    private static final int LEGACY_LARGE_LIST_FLAT_BYTES = 27;
    private static final long LEGACY_LARGE_LIST_CPU = 903_542L;
    private static final long LEGACY_LARGE_LIST_MEMORY = 3_329L;

    @Test
    void largeContainerGuardStaysWithinExplicitSizeAndExecutionCeilings() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                @MintingValidator class LargeBoundary {
                    @Entrypoint static boolean validate(
                            List<BigInteger> redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var stdlib = StdlibRegistry.defaultRegistry();
        var strict = new JulcCompiler(stdlib).compile(source).program();

        var values = new ArrayList<PlutusData>();
        for (int i = 0; i < 250; i++) values.add(PlutusData.integer(i));
        var context = PlutusData.constr(0, PlutusData.integer(0),
                new PlutusData.ListData(values),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        var vm = JulcVm.create();
        var strictResult = vm.evaluateWithArgs(strict, List.of(context));

        assertTrue(strictResult.isSuccess());
        int strictSize = UplcFlatEncoder.encodeProgram(strict).length;
        assertTrue(strictSize > LEGACY_LARGE_LIST_FLAT_BYTES);
        assertTrue(strictSize - LEGACY_LARGE_LIST_FLAT_BYTES <= 2_000,
                "strict container guard grew by "
                        + (strictSize - LEGACY_LARGE_LIST_FLAT_BYTES) + " bytes");
        assertTrue(strictResult.budgetConsumed().cpuSteps() < CARDANO_MAX_TX_CPU);
        assertTrue(strictResult.budgetConsumed().memoryUnits() < CARDANO_MAX_TX_MEMORY);
        assertTrue(strictResult.budgetConsumed().cpuSteps() > LEGACY_LARGE_LIST_CPU,
                "the regression control must observe the strict traversal cost");
        assertTrue(strictResult.budgetConsumed().memoryUnits() > LEGACY_LARGE_LIST_MEMORY,
                "the regression control must observe the strict traversal memory cost");

        System.out.printf("strict-boundary-cost legacyBytes=%d strictBytes=%d "
                        + "legacyCpu=%d strictCpu=%d legacyMem=%d strictMem=%d%n",
                LEGACY_LARGE_LIST_FLAT_BYTES, strictSize,
                LEGACY_LARGE_LIST_CPU,
                strictResult.budgetConsumed().cpuSteps(),
                LEGACY_LARGE_LIST_MEMORY,
                strictResult.budgetConsumed().memoryUnits());
    }

    @Test
    void nestedContainerRecordStaysWithinPracticalTransactionCeilings() {
        String source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import com.bloxbean.cardano.julc.ledger.ScriptContext;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                @MintingValidator class NestedBoundaryCost {
                    record Redeemer(List<Map<byte[], Optional<List<BigInteger>>>> batches) {}
                    @Entrypoint static boolean validate(Redeemer redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        var program = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source).program();
        var maps = new ArrayList<PlutusData>();
        for (int i = 0; i < 20; i++) {
            var integers = new ArrayList<PlutusData>();
            for (int j = 0; j < 10; j++) integers.add(PlutusData.integer(j));
            PlutusData value = PlutusData.constr(0, new PlutusData.ListData(integers));
            maps.add(PlutusData.map(
                    new PlutusData.Pair(PlutusData.bytes(new byte[]{(byte) i}), value),
                    new PlutusData.Pair(PlutusData.bytes(new byte[]{(byte) i}), value)));
        }
        var redeemer = PlutusData.constr(0, new PlutusData.ListData(maps));
        var result = JulcVm.create().evaluateWithArgs(program,
                List.of(PlutusData.constr(0, PlutusData.integer(0), redeemer,
                        PlutusData.constr(0, PlutusData.bytes(new byte[28])))));

        assertTrue(result.isSuccess());
        assertTrue(result.budgetConsumed().cpuSteps() < CARDANO_MAX_TX_CPU);
        assertTrue(result.budgetConsumed().memoryUnits() < CARDANO_MAX_TX_MEMORY);
        System.out.printf("strict-boundary-nested bytes=%d cpu=%d mem=%d%n",
                UplcFlatEncoder.encodeProgram(program).length,
                result.budgetConsumed().cpuSteps(), result.budgetConsumed().memoryUnits());
    }
}
