package org.julclang.benchmark.optimization;

import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.vm.OptimizationCostProfiles;

import java.util.ArrayList;
import java.util.List;

/** Reproducible ADR-032 evidence fixtures and release-note table entry point. */
public final class OptimizationEvidenceMain {

    private static final String O1_DROP_LIST_SOURCE = """
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
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
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcValue;
            import org.julclang.stdlib.lib.NativeValueLib;
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
            import org.julclang.stdlib.Builtins;
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

    private static final String O8_IMPORTS = """
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcValue;
            import org.julclang.stdlib.lib.NativeValueLib;
            import java.math.BigInteger;
            """;
    /** Two leading conversions of one Data argument; ADR-042 shares them at the safe profile. */
    private static final String O8_REPEATED_SOURCE = O8_IMPORTS + """
            class ValueSharingRepeated {
                static BigInteger repeated(PlutusData data, byte[] policy, byte[] token) {
                    return NativeValueLib.lookupCoin(
                                    policy, token, NativeValueLib.fromData(data))
                            + NativeValueLib.lookupCoin(
                                    policy, token, NativeValueLib.fromData(data));
                }
            }
            """;
    /** The manual workaround; kept in its own class so no dead method carries rule provenance. */
    private static final String O8_SHARED_SOURCE = O8_IMPORTS + """
            class ValueSharingShared {
                static BigInteger shared(PlutusData data, byte[] policy, byte[] token) {
                    JulcValue value = NativeValueLib.fromData(data);
                    return NativeValueLib.lookupCoin(policy, token, value)
                            + NativeValueLib.lookupCoin(policy, token, value);
                }
            }
            """;

    private static final String O9_IMPORTS = """
            import org.julclang.core.types.JulcArray;
            import org.julclang.core.types.JulcList;
            import java.math.BigInteger;
            """;
    /**
     * The WingRiders pool-validator shape (ADR-043): three lists indexed once each inside a
     * loop driven by a redeemer index list. Every list is promoted at PV11_COSTED.
     */
    private static final String O9_REQUEST_LOOP_SOURCE = O9_IMPORTS + """
            class ListIndexRequestLoop {
                static BigInteger requests(JulcList<BigInteger> inputs, JulcList<BigInteger> outputs,
                                           JulcList<BigInteger> requestIndices) {
                    BigInteger total = BigInteger.ZERO;
                    int i = 0;
                    int n = requestIndices.size();
                    while (i < n) {
                        BigInteger requestIndex = requestIndices.get(i);
                        total = total.add(inputs.get(requestIndex)).add(outputs.get(i + 1));
                        i = i + 1;
                    }
                    return total;
                }
            }
            """;
    /** Two sites on one parameter; the costed bytes must equal the manual array form below. */
    private static final String O9_TWO_SITES_SOURCE = O9_IMPORTS + """
            class ListIndexTwoSites {
                static BigInteger twoSites(JulcList<BigInteger> items, BigInteger first, BigInteger second) {
                    return items.get(first).add(items.get(second));
                }
            }
            """;
    /** The manual array form; kept in its own class so no dead method carries rule provenance. */
    private static final String O9_MANUAL_ARRAY_SOURCE = O9_IMPORTS + """
            class ListIndexManualArray {
                static BigInteger manualArray(JulcList<BigInteger> items, BigInteger first, BigInteger second) {
                    JulcArray<BigInteger> array = items.toArray();
                    return array.get(first).add(array.get(second));
                }
            }
            """;

    private static final String O15_IMPORTS = """
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.TxInfo;
            import java.math.BigInteger;
            """;
    /** ADR-044 (O15): the corpus shape, one record field projected on every path. */
    private static final String O15_REPEATED_SOURCE = O15_IMPORTS + """
            class ProjectionSharingRepeated {
                record Box(BigInteger amount, byte[] owner) {}
                static BigInteger repeated(Box b, BigInteger limit) {
                    if (b.amount().compareTo(limit) > 0) {
                        return b.amount().subtract(limit);
                    }
                    return b.amount().add(limit);
                }
            }
            """;
    /** The hand-written binding; kept in its own class so no dead method carries rule provenance. */
    private static final String O15_MANUAL_SOURCE = O15_IMPORTS + """
            class ProjectionSharingManual {
                record Box(BigInteger amount, byte[] owner) {}
                static BigInteger manual(Box b, BigInteger limit) {
                    BigInteger amount = b.amount();
                    if (amount.compareTo(limit) > 0) {
                        return amount.subtract(limit);
                    }
                    return amount.add(limit);
                }
            }
            """;
    /** The validator shape: {@code outputs} twice (shared as one chain), {@code fee} once per branch (shared prefix). */
    private static final String O15_LEDGER_SOURCE = O15_IMPORTS + """
            class ProjectionSharingLedger {
                static BigInteger ledger(TxInfo txInfo) {
                    if (txInfo.outputs().isEmpty()) {
                        return txInfo.fee();
                    }
                    BigInteger count = BigInteger.valueOf(txInfo.outputs().size());
                    return count.add(txInfo.fee());
                }
            }
            """;

    private static final String O12_EXP_MOD_SOURCE = """
            import org.julclang.stdlib.lib.MathLib;
            import java.math.BigInteger;
            class ExpModIdiomEvidence {
                static BigInteger powThenMod(
                        BigInteger base, BigInteger exponent, BigInteger modulus) {
                    return MathLib.pow(base, exponent) % modulus;
                }
                static BigInteger explicitExpMod(
                        BigInteger base, BigInteger exponent, BigInteger modulus) {
                    return MathLib.expMod(base, exponent, modulus);
                }
            }
            """;

    private static final String O13_EXP_MOD_LITERAL_SOURCE = """
            import org.julclang.stdlib.lib.MathLib;
            import java.math.BigInteger;
            class ExpModLiteralEvidence {
                static BigInteger literals() {
                    return MathLib.expMod(
                                    BigInteger.valueOf(2), BigInteger.valueOf(5),
                                    BigInteger.valueOf(13))
                            + MathLib.expMod(
                                    BigInteger.valueOf(2), BigInteger.valueOf(-1),
                                    BigInteger.valueOf(5))
                            + MathLib.expMod(
                                    BigInteger.valueOf(0), BigInteger.valueOf(0),
                                    BigInteger.valueOf(7));
                }
            }
            """;

    private static final String O13_EXP_MOD_INVALID_LITERAL_SOURCE = """
            import org.julclang.stdlib.lib.MathLib;
            import java.math.BigInteger;
            class ExpModInvalidLiteralEvidence {
                static BigInteger zeroModulus() {
                    return MathLib.expMod(
                            BigInteger.valueOf(2), BigInteger.valueOf(5),
                            BigInteger.ZERO);
                }
            }
            """;

    private static final String O13_EXP_MOD_FAILURE_MATRIX_SOURCE = """
            import org.julclang.stdlib.lib.MathLib;
            import java.math.BigInteger;
            class ExpModFailureMatrixEvidence {
                static BigInteger invalid(long mode) {
                    if (mode == 0) {
                        return MathLib.expMod(
                                BigInteger.valueOf(2), BigInteger.valueOf(5),
                                BigInteger.ZERO);
                    }
                    if (mode == 1) {
                        return MathLib.expMod(
                                BigInteger.valueOf(2), BigInteger.valueOf(5),
                                BigInteger.valueOf(-7));
                    }
                    return MathLib.expMod(
                            BigInteger.valueOf(2), BigInteger.valueOf(-1),
                            BigInteger.valueOf(4));
                }
            }
            """;

    private static final String AGGREGATE_SOURCE = """
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.MathLib;
            import java.math.BigInteger;
            class Pv11AggregateEvidence {
                static PlutusData validateLike(
                        PlutusData data, long dropCount, BigInteger minimum) {
                    JulcList<PlutusData> items = Builtins.unListData(data);
                    JulcList<PlutusData> remaining = items.drop(dropCount);
                    if (remaining.isEmpty()) return Builtins.iData(0);
                    BigInteger head = Builtins.unIData(remaining.head());
                    if (head.compareTo(minimum) < 0) return Builtins.iData(0);
                    BigInteger bonus = MathLib.expMod(
                            BigInteger.valueOf(2), BigInteger.valueOf(5),
                            BigInteger.valueOf(13));
                    return Builtins.iData(head.add(bonus));
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
        System.out.println();
        System.out.print(o8ValueSharingComparison().toMarkdown());
        System.out.println();
        System.out.print(o8ManualSharingControlComparison().toMarkdown());
        System.out.println();
        System.out.print(o9RequestLoopComparison().toMarkdown());
        System.out.println();
        System.out.print(o9TwoSitesComparison().toMarkdown());
        System.out.println();
        System.out.print(o9ManualArrayControlComparison().toMarkdown());
        System.out.println();
        System.out.print(o15ProjectionSharingComparison().toMarkdown());
        System.out.println();
        System.out.print(o15ManualBindingControlComparison().toMarkdown());
        System.out.println();
        System.out.print(o15LedgerProjectionComparison().toMarkdown());
        System.out.println();
        System.out.print(o12ExpModIdiomExperiment().toMarkdown());
        System.out.println();
        System.out.print(o13ExpModLiteralComparison().toMarkdown());
        System.out.println();
        System.out.print(o13ExpModInvalidLiteralComparison().toMarkdown());
        System.out.println();
        System.out.print(aggregatePv11SafeComparison().toMarkdown());
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

    /**
     * O8 (ADR-042): the {@code repeated} source converts the same Data twice in leading
     * position; the safe profile shares one conversion and must match the hand-written
     * {@code shared} source byte for byte. Valid and malformed inputs must agree with
     * BASELINE on result, traces and failure text.
     */
    public static OptimizationBenchmarkRunner.Comparison o8ValueSharingComparison() {
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o8-native-value-sharing", O8_REPEATED_SOURCE, "repeated", o8Cases()),
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    /** The manual-sharing control: BASELINE and PV11_SAFE of {@code shared} carry no O8 rule. */
    public static OptimizationBenchmarkRunner.Comparison o8ManualSharingControlComparison() {
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o8-native-value-shared-control", O8_SHARED_SOURCE, "shared", o8Cases()),
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    private static List<OptimizationBenchmarkRunner.InputCase> o8Cases() {
        byte[] policy = new byte[] {1, 2, 3};
        byte[] token = new byte[] {4, 5};
        return List.of(
                OptimizationBenchmarkRunner.InputCase.of(
                        "valid", sampleValueData(policy, token),
                        PlutusData.bytes(policy), PlutusData.bytes(token)),
                OptimizationBenchmarkRunner.InputCase.of(
                        "malformed", PlutusData.integer(1),
                        PlutusData.bytes(policy), PlutusData.bytes(token)));
    }

    /**
     * O9 (ADR-043): the request loop indexes three lists once each per iteration; PV11_COSTED
     * converts each list to an array once before the loop. Measured against PV11_SAFE so the
     * delta is the promotion alone. Inputs are valid so results, traces and failures agree.
     */
    public static OptimizationBenchmarkRunner.Comparison o9RequestLoopComparison() {
        return OptimizationBenchmarkRunner.compareLevelsWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o9-list-index-request-loop", O9_REQUEST_LOOP_SOURCE, "requests", o9LoopCases()),
                OptimizationLevel.PV11_SAFE,
                OptimizationLevel.PV11_COSTED,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    /** Two sites on one list: the costed program must be the manual array program byte for byte. */
    public static OptimizationBenchmarkRunner.Comparison o9TwoSitesComparison() {
        return OptimizationBenchmarkRunner.compareLevelsWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o9-list-index-two-sites", O9_TWO_SITES_SOURCE, "twoSites", o9TwoSiteCases()),
                OptimizationLevel.PV11_SAFE,
                OptimizationLevel.PV11_COSTED,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    /** ADR-044 (O15): PV11_SAFE with projection sharing off versus on, so the delta is O15 alone. */
    public static OptimizationBenchmarkRunner.Comparison o15ProjectionSharingComparison() {
        return OptimizationBenchmarkRunner.compareRuleWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o15-projection-sharing", O15_REPEATED_SOURCE, "repeated", o15BoxCases()),
                OptimizationLevel.PV11_SAFE,
                "pv11.o15.projection-sharing",
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    /** The manual binding control: {@code manual} carries no O15 rule with the switch off or on. */
    public static OptimizationBenchmarkRunner.Comparison o15ManualBindingControlComparison() {
        return OptimizationBenchmarkRunner.compareRuleWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o15-projection-manual-control", O15_MANUAL_SOURCE, "manual", o15BoxCases()),
                OptimizationLevel.PV11_SAFE,
                "pv11.o15.projection-sharing",
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    /** The ledger shape: a shared chain plus a shared fields prefix over a {@code TxInfo}. */
    public static OptimizationBenchmarkRunner.Comparison o15LedgerProjectionComparison() {
        return OptimizationBenchmarkRunner.compareRuleWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o15-projection-ledger", O15_LEDGER_SOURCE, "ledger", List.of(
                                OptimizationBenchmarkRunner.InputCase.of("two-outputs",
                                        txInfoData(PlutusData.constr(0), PlutusData.constr(0))),
                                OptimizationBenchmarkRunner.InputCase.of("no-outputs", txInfoData()),
                                OptimizationBenchmarkRunner.InputCase.of("not-a-record", PlutusData.integer(1)))),
                OptimizationLevel.PV11_SAFE,
                "pv11.o15.projection-sharing",
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    private static List<OptimizationBenchmarkRunner.InputCase> o15BoxCases() {
        var box = PlutusData.constr(0, PlutusData.integer(7), PlutusData.bytes(new byte[28]));
        return List.of(
                OptimizationBenchmarkRunner.InputCase.of("above", box, PlutusData.integer(5)),
                OptimizationBenchmarkRunner.InputCase.of("below", box, PlutusData.integer(10)),
                OptimizationBenchmarkRunner.InputCase.of("not-a-record", PlutusData.integer(1), PlutusData.integer(5)),
                OptimizationBenchmarkRunner.InputCase.of("bad-amount",
                        PlutusData.constr(0, PlutusData.bytes(new byte[1]), PlutusData.bytes(new byte[28])), PlutusData.integer(5)));
    }

    /** A V3 {@code TxInfo} with the given outputs, fee 2,000,000 and empty everything else. */
    private static PlutusData txInfoData(PlutusData... outputs) {
        var trueValue = PlutusData.constr(1);
        var lower = PlutusData.constr(0, PlutusData.constr(0), trueValue);
        var upper = PlutusData.constr(0, PlutusData.constr(2), trueValue);
        return PlutusData.constr(0,
                PlutusData.list(), PlutusData.list(), PlutusData.list(outputs), PlutusData.integer(2_000_000),
                PlutusData.map(), PlutusData.list(), PlutusData.map(), PlutusData.constr(0, lower, upper),
                PlutusData.list(), PlutusData.map(), PlutusData.map(), PlutusData.bytes(new byte[32]),
                PlutusData.map(), PlutusData.list(), PlutusData.constr(1), PlutusData.constr(1));
    }

    /** The manual array control: PV11_SAFE and PV11_COSTED of {@code manualArray} carry no O9 rule. */
    public static OptimizationBenchmarkRunner.Comparison o9ManualArrayControlComparison() {
        return OptimizationBenchmarkRunner.compareLevelsWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o9-list-index-manual-array-control", O9_MANUAL_ARRAY_SOURCE, "manualArray", o9TwoSiteCases()),
                OptimizationLevel.PV11_SAFE,
                OptimizationLevel.PV11_COSTED,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    private static List<OptimizationBenchmarkRunner.InputCase> o9LoopCases() {
        var cases = new ArrayList<OptimizationBenchmarkRunner.InputCase>();
        for (int requests : List.of(0, 1, 2, 4, 8, 16)) {
            var indices = new PlutusData[requests];
            for (int r = 0; r < requests; r++) indices[r] = PlutusData.integer(requests - 1 - r);
            cases.add(OptimizationBenchmarkRunner.InputCase.of(
                    "requests-" + requests, listOfLength(16), listOfLength(17), PlutusData.list(indices)));
        }
        return cases;
    }

    private static List<OptimizationBenchmarkRunner.InputCase> o9TwoSiteCases() {
        return List.of(
                arrayInput("length-8-0-1", listOfLength(8), 0, 1),
                arrayInput("length-8-6-7", listOfLength(8), 6, 7),
                arrayInput("length-64-0-63", listOfLength(64), 0, 63));
    }

    public static OptimizationBenchmarkRunner.Comparison o12ExpModIdiomExperiment() {
        var cases = List.of(
                integerInput("ordinary", 2, 5, 13),
                integerInput("zero-exponent", 0, 0, 7),
                integerInput("negative-exponent", 2, -1, 5),
                integerInput("zero-modulus", 2, 5, 0),
                integerInput("negative-modulus", 2, 5, -7));
        return OptimizationBenchmarkRunner.compareResearchFixturesWithJavaAndTruffle(
                "o12-exp-mod-idiom-experiment",
                new OptimizationBenchmarkRunner.Fixture(
                        "o12-pow-mod", O12_EXP_MOD_SOURCE, "powThenMod", cases),
                new OptimizationBenchmarkRunner.Fixture(
                        "o12-exp-mod", O12_EXP_MOD_SOURCE, "explicitExpMod", cases),
                "pv11.o12.exp-mod-idiom-research",
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    public static OptimizationBenchmarkRunner.Comparison o13ExpModLiteralComparison() {
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o13-exp-mod-literal-fold",
                        O13_EXP_MOD_LITERAL_SOURCE,
                        "literals",
                        List.of(OptimizationBenchmarkRunner.InputCase.of("literal-suite"))),
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    public static OptimizationBenchmarkRunner.Comparison o13ExpModInvalidLiteralComparison() {
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o13-exp-mod-invalid-literal",
                        O13_EXP_MOD_INVALID_LITERAL_SOURCE,
                        "zeroModulus",
                        List.of(OptimizationBenchmarkRunner.InputCase.of("zero-modulus"))),
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    /** Failure matrix is checked in tests but omitted from release-note output. */
    public static OptimizationBenchmarkRunner.Comparison o13ExpModFailureMatrix() {
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "o13-exp-mod-failure-matrix",
                        O13_EXP_MOD_FAILURE_MATRIX_SOURCE,
                        "invalid",
                        List.of(
                                OptimizationBenchmarkRunner.InputCase.of(
                                        "zero-modulus", PlutusData.integer(0)),
                                OptimizationBenchmarkRunner.InputCase.of(
                                        "negative-modulus", PlutusData.integer(1)),
                                OptimizationBenchmarkRunner.InputCase.of(
                                        "non-invertible", PlutusData.integer(2)))),
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    public static OptimizationBenchmarkRunner.Comparison aggregatePv11SafeComparison() {
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture(
                        "pv11-safe-aggregate-validator-like",
                        AGGREGATE_SOURCE,
                        "validateLike",
                        List.of(
                                input("accept", sampleList(), 1, 15),
                                input("below-minimum", sampleList(), 1, 25),
                                input("negative-drop", sampleList(), -1, 5),
                                input("empty-after-drop", sampleList(), 3, 5),
                                input("over-drop", sampleList(), 8, 5),
                                input("empty-input", PlutusData.list(), 0, 5),
                                input("malformed-list", PlutusData.integer(1), 0, 5),
                                input("malformed-head", PlutusData.list(
                                        PlutusData.bytes(new byte[] {1})), 0, 5))),
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

    private static OptimizationBenchmarkRunner.InputCase boolInput(
            String id, boolean value, long mode) {
        return OptimizationBenchmarkRunner.InputCase.of(
                id,
                PlutusData.constr(value ? 1 : 0),
                PlutusData.integer(mode));
    }

    private static OptimizationBenchmarkRunner.InputCase arrayInput(
            String id, PlutusData list, long first, long second) {
        return OptimizationBenchmarkRunner.InputCase.of(
                id, list, PlutusData.integer(first), PlutusData.integer(second));
    }

    private static OptimizationBenchmarkRunner.InputCase integerInput(
            String id, long first, long second, long third) {
        return OptimizationBenchmarkRunner.InputCase.of(
                id,
                PlutusData.integer(first),
                PlutusData.integer(second),
                PlutusData.integer(third));
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

    private static PlutusData sampleValueData(byte[] policy, byte[] token) {
        return PlutusData.map(new PlutusData.Pair(
                PlutusData.bytes(policy),
                PlutusData.map(new PlutusData.Pair(
                        PlutusData.bytes(token),
                        PlutusData.integer(42)))));
    }

    private static PlutusData listOfLength(int length) {
        var items = new PlutusData[length];
        for (int i = 0; i < length; i++) {
            items[i] = PlutusData.integer(i);
        }
        return PlutusData.list(items);
    }
}
