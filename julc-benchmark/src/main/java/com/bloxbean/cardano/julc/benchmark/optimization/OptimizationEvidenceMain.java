package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.compiler.OptimizationLevel;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.DefaultUni;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
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

    private static final String O7_NATIVE_VALUE_SOURCE = """
            import com.bloxbean.cardano.julc.core.PlutusData;
            import com.bloxbean.cardano.julc.core.types.JulcValue;
            import com.bloxbean.cardano.julc.stdlib.lib.NativeValueLib;
            import java.math.BigInteger;
            class NativeValueEvidence {
                static boolean exercise(PlutusData data, byte[] policy, byte[] token) {
                    JulcValue original = NativeValueLib.fromData(data);
                    JulcValue inserted = NativeValueLib.insertCoin(
                            policy, token, BigInteger.valueOf(3), original);
                    BigInteger quantity = NativeValueLib.lookupCoin(
                            policy, token, inserted);
                    JulcValue scaled = NativeValueLib.scale(quantity, inserted);
                    JulcValue merged = NativeValueLib.union(original, scaled);
                    PlutusData encoded = NativeValueLib.toData(merged);
                    JulcValue restored = NativeValueLib.fromData(encoded);
                    return NativeValueLib.contains(restored, original);
                }
            }
            """;

    private static final String O2_CASE_BOOL_SOURCE = """
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            class BoolCaseEvidence {
                static long selected(boolean condition, long mode) {
                    long encoded = condition ? 1 : 0;
                    boolean observed = Builtins.unIData(Builtins.trace(
                            "condition", Builtins.iData(encoded))) == 1;
                    if (observed) {
                        if (mode == 1) return Builtins.unIData(Builtins.error());
                        return Builtins.unIData(Builtins.trace(
                                "then", Builtins.iData(11)));
                    } else {
                        if (mode == 2) return Builtins.unIData(Builtins.error());
                        return Builtins.unIData(Builtins.trace(
                                "else", Builtins.iData(22)));
                    }
                }
            }
            """;

    private OptimizationEvidenceMain() {
    }

    public static void main(String[] args) {
        System.out.print(o1DropListComparison().toMarkdown());
        System.out.println();
        System.out.print(o1DropListComposedComparison().toMarkdown());
        System.out.println();
        System.out.print(o7NativeValueComparison().toMarkdown());
        System.out.println();
        System.out.print(o2CaseBoolComparison().toMarkdown());
        System.out.println();
        System.out.print(o3CaseListExperiment().toMarkdown());
        System.out.println();
        System.out.print(o4CasePairExperiment().toMarkdown());
        System.out.println();
        System.out.print(o5CaseIntegerExperiment().toMarkdown());
        System.out.println();
        System.out.print(o6CaseUnitExperiment().toMarkdown());
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

    /**
     * O7 establishes a typed representation boundary rather than an optimizer
     * rewrite, so BASELINE and PV11_SAFE must remain byte- and cost-identical.
     */
    public static OptimizationBenchmarkRunner.Comparison o7NativeValueComparison() {
        byte[] policy = new byte[] {1, 2, 3};
        byte[] token = new byte[] {4, 5};
        var valueData = PlutusData.map(new PlutusData.Pair(
                PlutusData.bytes(policy),
                PlutusData.map(new PlutusData.Pair(
                        PlutusData.bytes(token),
                        PlutusData.integer(42)))));
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o7-typed-native-value",
                        O7_NATIVE_VALUE_SOURCE,
                        "exercise",
                        List.of(
                                OptimizationBenchmarkRunner.InputCase.of(
                                        "present", valueData,
                                        PlutusData.bytes(policy), PlutusData.bytes(token)),
                                OptimizationBenchmarkRunner.InputCase.of(
                                        "absent", valueData,
                                        PlutusData.bytes(policy), PlutusData.bytes(new byte[] {9})),
                                OptimizationBenchmarkRunner.InputCase.of(
                                        "malformed-data", PlutusData.integer(1),
                                        PlutusData.bytes(policy), PlutusData.bytes(token)))),
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    public static OptimizationBenchmarkRunner.Comparison o2CaseBoolComparison() {
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o2-case-bool",
                        O2_CASE_BOOL_SOURCE,
                        "selected",
                        List.of(
                                boolInput("true", true, 0),
                                boolInput("false", false, 0),
                                boolInput("true-unselected-error", true, 2),
                                boolInput("false-unselected-error", false, 1),
                                boolInput("true-selected-error", true, 1),
                                boolInput("false-selected-error", false, 2))),
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    public static OptimizationBenchmarkRunner.Comparison o3CaseListExperiment() {
        var xs = Term.var(1);
        var chooseList = force(Term.builtin(DefaultFun.ChooseList), 2);
        var baseline = Term.lam("xs", Term.force(Term.apply(
                Term.apply(
                        Term.apply(chooseList, xs),
                        Term.delay(Term.error())),
                Term.delay(Term.apply(force(Term.builtin(DefaultFun.HeadList), 1), xs)))));
        var candidate = Term.lam("xs", new Term.Case(
                xs,
                List.of(
                        Term.lam("head", Term.lam("tail", Term.var(2))),
                        Term.error())));
        return OptimizationBenchmarkRunner.compareTermsWithJavaAndTruffle(
                "o3-case-list-head-experiment",
                baseline,
                candidate,
                List.of(
                        termInput("empty", integerList()),
                        termInput("singleton", integerList(7)),
                        termInput("three", integerList(7, 8, 9))),
                "pv11.o3.case-list-research",
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    public static OptimizationBenchmarkRunner.Comparison o4CasePairExperiment() {
        var pair = Term.var(1);
        var fst = Term.apply(force(Term.builtin(DefaultFun.FstPair), 2), pair);
        var snd = Term.apply(force(Term.builtin(DefaultFun.SndPair), 2), pair);
        var baseline = Term.lam("pair", Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger), fst), snd));
        var candidate = Term.lam("pair", new Term.Case(
                pair,
                List.of(Term.lam("first", Term.lam("second", Term.apply(
                        Term.apply(Term.builtin(DefaultFun.AddInteger), Term.var(2)),
                        Term.var(1)))))));
        return OptimizationBenchmarkRunner.compareTermsWithJavaAndTruffle(
                "o4-case-pair-projection-experiment",
                baseline,
                candidate,
                List.of(
                        termInput("positive", Term.const_(new Constant.PairConst(
                                Constant.integer(3), Constant.integer(4)))),
                        termInput("negative", Term.const_(new Constant.PairConst(
                                Constant.integer(-5), Constant.integer(2))))),
                "pv11.o4.case-pair-research",
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    public static OptimizationBenchmarkRunner.Comparison o5CaseIntegerExperiment() {
        var integer = Term.var(1);
        var baseline = Term.lam("integer", lazyIf(equalsInteger(integer, 0),
                Term.const_(Constant.integer(10)),
                lazyIf(equalsInteger(integer, 1),
                        Term.const_(Constant.integer(20)),
                        lazyIf(equalsInteger(integer, 2),
                                Term.const_(Constant.integer(30)),
                                Term.error()))));
        var candidate = Term.lam("integer", new Term.Case(
                integer,
                List.of(
                        Term.const_(Constant.integer(10)),
                        Term.const_(Constant.integer(20)),
                        Term.const_(Constant.integer(30)))));
        return OptimizationBenchmarkRunner.compareResearchTermsWithJavaAndTruffle(
                "o5-case-integer-dense-experiment",
                baseline,
                candidate,
                List.of(
                        termInput("negative", Term.const_(Constant.integer(-1))),
                        termInput("zero", Term.const_(Constant.integer(0))),
                        termInput("one", Term.const_(Constant.integer(1))),
                        termInput("two", Term.const_(Constant.integer(2))),
                        termInput("out-of-range", Term.const_(Constant.integer(3)))),
                "pv11.o5.case-integer-research",
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    public static OptimizationBenchmarkRunner.Comparison o6CaseUnitExperiment() {
        var unit = Term.var(1);
        var continuation = Term.apply(
                Term.apply(force(Term.builtin(DefaultFun.Trace), 1),
                        Term.const_(Constant.string("unit"))),
                Term.const_(Constant.integer(7)));
        var baseline = Term.lam("unit", Term.apply(
                Term.apply(force(Term.builtin(DefaultFun.ChooseUnit), 1), unit),
                continuation));
        var candidate = Term.lam("unit", new Term.Case(unit, List.of(continuation)));
        return OptimizationBenchmarkRunner.compareTermsWithJavaAndTruffle(
                "o6-case-unit-sequencing-experiment",
                baseline,
                candidate,
                List.of(termInput("unit", Term.const_(Constant.unit()))),
                "pv11.o6.case-unit-research",
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

    private static OptimizationBenchmarkRunner.InputCase boolInput(
            String id, boolean value, long mode) {
        return OptimizationBenchmarkRunner.InputCase.of(
                id,
                PlutusData.constr(value ? 1 : 0),
                PlutusData.integer(mode));
    }

    private static OptimizationBenchmarkRunner.TermInputCase termInput(
            String id, Term... arguments) {
        return OptimizationBenchmarkRunner.TermInputCase.of(id, arguments);
    }

    private static Term integerList(long... values) {
        var constants = new ArrayList<Constant>();
        for (long value : values) constants.add(Constant.integer(value));
        return Term.const_(new Constant.ListConst(DefaultUni.INTEGER, constants));
    }

    private static Term force(Term term, int count) {
        for (int i = 0; i < count; i++) term = Term.force(term);
        return term;
    }

    private static Term equalsInteger(Term value, long expected) {
        return Term.apply(
                Term.apply(Term.builtin(DefaultFun.EqualsInteger), value),
                Term.const_(Constant.integer(expected)));
    }

    private static Term lazyIf(Term condition, Term whenTrue, Term whenFalse) {
        return Term.force(Term.apply(
                Term.apply(
                        Term.apply(force(Term.builtin(DefaultFun.IfThenElse), 1), condition),
                        Term.delay(whenTrue)),
                Term.delay(whenFalse)));
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
