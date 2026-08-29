package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.compiler.OptimizationLevel;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.OptimizationCostProfiles;

import java.util.ArrayList;
import java.util.List;

/** Reproducible ADR-032 evidence fixtures and release-note table entry point. */
public final class OptimizationEvidenceMain {

    private static final String O1_DROP_LIST_SOURCE = """
            import com.bloxbean.cardano.julc.core.PlutusData;
            import com.bloxbean.cardano.julc.core.types.JulcList;
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            class DropListEvidence {
                static PlutusData receiver(PlutusData data, long mode) {
                    if (mode == 1) return Builtins.error();
                    return Builtins.trace("receiver", data);
                }
                static long count(long value, long mode) {
                    if (mode == 2) return Builtins.unIData(Builtins.error());
                    return Builtins.unIData(Builtins.trace("count", Builtins.iData(value)));
                }
                static PlutusData drop(PlutusData data, long n, long mode) {
                    JulcList<PlutusData> items = Builtins.unListData(receiver(data, mode));
                    return Builtins.listData(items.drop(count(n, mode)));
                }
                static PlutusData dropTwice(PlutusData data, long first, long second) {
                    JulcList<PlutusData> items = Builtins.unListData(data);
                    return Builtins.listData(items.drop(first).drop(second));
                }
            }
            """;

    private OptimizationEvidenceMain() {
    }

    public static void main(String[] args) {
        System.out.print(o1DropListComparison().toMarkdown());
        System.out.println();
        System.out.print(o1DropListComposedComparison().toMarkdown());
    }

    public static OptimizationBenchmarkRunner.Comparison o1DropListComparison() {
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                o1DropListFixture(),
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    public static OptimizationBenchmarkRunner.Comparison o1DropListComposedComparison() {
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o1-drop-list-composed",
                        O1_DROP_LIST_SOURCE,
                        "dropTwice",
                        List.of(
                                input("one-then-one", sampleList(), 1, 1),
                                input("zero-then-two", sampleList(), 0, 2),
                                input("over-then-one", sampleList(), 5, 1),
                                input("negative-then-one", sampleList(), -1, 1))),
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    /** Bounded semantic matrix; intentionally omitted from release-note output. */
    public static OptimizationBenchmarkRunner.Comparison o1DropListBoundaryMatrix() {
        var cases = new ArrayList<OptimizationBenchmarkRunner.InputCase>();
        for (int length = 0; length <= 8; length++) {
            var list = listOfLength(length);
            for (int count = -3; count <= 12; count++) {
                cases.add(input("length-" + length + "-count-" + count,
                        list, count, 0));
            }
        }
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o1-drop-list-boundary-matrix",
                        O1_DROP_LIST_SOURCE,
                        "drop",
                        cases),
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    static OptimizationBenchmarkRunner.Fixture o1DropListFixture() {
        var list = sampleList();
        return new OptimizationBenchmarkRunner.Fixture(
                "o1-drop-list",
                O1_DROP_LIST_SOURCE,
                "drop",
                List.of(
                        input("negative", list, -1, 0),
                        input("zero", list, 0, 0),
                        input("one", list, 1, 0),
                        input("equal-length", list, 3, 0),
                        input("over-length", list, 5, 0),
                        input("empty", PlutusData.list(), 2, 0),
                        input("receiver-failure", list, 1, 1),
                        input("count-failure", list, 1, 2)));
    }

    private static OptimizationBenchmarkRunner.InputCase input(
            String id, PlutusData list, long count, long mode) {
        return OptimizationBenchmarkRunner.InputCase.of(
                id,
                list,
                PlutusData.integer(count),
                PlutusData.integer(mode));
    }

    private static PlutusData sampleList() {
        return PlutusData.list(
                PlutusData.integer(10),
                PlutusData.integer(20),
                PlutusData.integer(30));
    }

    private static PlutusData listOfLength(int length) {
        var items = new PlutusData[length];
        for (int i = 0; i < length; i++) {
            items[i] = PlutusData.integer(i);
        }
        return PlutusData.list(items);
    }
}
