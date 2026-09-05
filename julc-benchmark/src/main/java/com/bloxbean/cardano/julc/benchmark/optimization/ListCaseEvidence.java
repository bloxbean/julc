package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.compiler.OptimizationLevel;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.OptimizationCostProfiles;

import java.util.ArrayList;
import java.util.List;

/** Compiled-source evidence for ADR-034 / #110; each loop family is checked separately. */
public final class ListCaseEvidence {
    private ListCaseEvidence() {}

    public static final String SOURCE = """
            import java.math.BigInteger;
            import com.bloxbean.cardano.julc.core.PlutusData;
            import com.bloxbean.cardano.julc.core.types.JulcList;
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            import com.bloxbean.cardano.julc.stdlib.lib.MathLib;
            class ListCaseEvidenceSource {
                static BigInteger sum(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs) { acc = acc.add(x); }
                    return acc;
                }
                static BigInteger traced(PlutusData data) {
                    JulcList<BigInteger> xs = (JulcList)(Object) Builtins.asList(Builtins.trace("input", data));
                    BigInteger acc = Builtins.unIData(Builtins.trace("init", Builtins.iData(0)));
                    for (BigInteger x : xs) {
                        acc = Builtins.unIData(Builtins.trace("item", Builtins.iData(acc.add(x))));
                    }
                    return acc;
                }
                static BigInteger unchecked(PlutusData data) {
                    JulcList<BigInteger> xs = (JulcList)(Object) data;
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs) { acc = acc.add(x); }
                    return acc;
                }
                static BigInteger stop(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs) {
                        acc = Builtins.unIData(Builtins.trace("item", Builtins.iData(acc.add(x))));
                        if (x.equals(BigInteger.ZERO)) { break; }
                    }
                    return acc;
                }
                static BigInteger multi(JulcList<BigInteger> xs) {
                    BigInteger sum = BigInteger.ZERO;
                    BigInteger count = BigInteger.ZERO;
                    for (BigInteger x : xs) {
                        sum = sum.add(x); count = count.add(BigInteger.ONE);
                        if (x.equals(BigInteger.ZERO)) { break; }
                    }
                    return sum.multiply(BigInteger.TEN).add(count);
                }
                static BigInteger nested(JulcList<JulcList<BigInteger>> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (JulcList<BigInteger> row : xs) {
                        for (BigInteger x : row) { acc = acc.add(x); }
                    }
                    return acc;
                }
                static BigInteger failSelected(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs) {
                        acc = Builtins.unIData(Builtins.trace("item", Builtins.iData(x)));
                        if (x.equals(BigInteger.ZERO)) { acc = Builtins.unIData(Builtins.error()); }
                    }
                    return acc;
                }
                record Item(BigInteger amount) {}
                static BigInteger records(JulcList<Item> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (Item x : xs) { acc = acc.add(x.amount()); }
                    return acc;
                }
                static BigInteger effects(JulcList<BigInteger> xs) {
                    for (BigInteger x : xs) { Builtins.trace("item", Builtins.iData(x)); }
                    return BigInteger.ZERO;
                }
                static BigInteger mutualA(JulcList<BigInteger> xs, long n) {
                    if (n <= 0) { return BigInteger.ZERO; }
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs) { acc = acc.add(x); }
                    return acc.add(mutualB(xs, n - 1));
                }
                static BigInteger mutualB(JulcList<BigInteger> xs, long n) {
                    if (n <= 0) { return BigInteger.ZERO; }
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs) { acc = acc.add(x); }
                    return acc.add(mutualA(xs, n - 1));
                }
                static BigInteger aggregate(JulcList<BigInteger> xs, long dropCount, BigInteger minimum) {
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs.drop(dropCount)) { acc = acc.add(x); }
                    if (acc.compareTo(minimum) < 0) { return BigInteger.ZERO; }
                    return acc.add(MathLib.expMod(BigInteger.TWO, BigInteger.valueOf(5), BigInteger.valueOf(13)));
                }
                static BigInteger recursive(JulcList<BigInteger> xs, long n) {
                    if (n <= 0) { return BigInteger.ZERO; }
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs) { acc = acc.add(x); }
                    return acc.add(recursive(xs, n - 1));
                }
            }
            """;

    public static List<OptimizationBenchmarkRunner.Comparison> comparisons() {
        var bad = PlutusData.bytes(new byte[]{1});
        var flatInputs = List.of(
                input("empty", PlutusData.list()),
                input("singleton", integers(7)),
                input("many", integers(3, -2, 5)),
                input("break", integers(3, 0, 5)),
                input("bad-first", PlutusData.list(bad)),
                input("bad-later", PlutusData.list(PlutusData.integer(3), bad)),
                input("break-before-bad", PlutusData.list(PlutusData.integer(0), bad)),
                input("bad-outer", bad));
        var comparisons = new ArrayList<OptimizationBenchmarkRunner.Comparison>();
        for (var method : List.of("sum", "traced", "stop", "multi", "failSelected", "unchecked", "effects")) {
            comparisons.add(compare(method, flatInputs));
        }
        comparisons.add(compare("nested", List.of(
                input("empty", PlutusData.list()),
                input("nested", PlutusData.list(integers(1, 2), integers(), integers(3))),
                input("bad-row", PlutusData.list(bad)),
                input("bad-element", PlutusData.list(PlutusData.list(PlutusData.integer(1), bad))))));
        comparisons.add(compare("recursive", List.of(
                OptimizationBenchmarkRunner.InputCase.of("three", integers(1, 2, 3), PlutusData.integer(3)),
                OptimizationBenchmarkRunner.InputCase.of("unselected", bad, PlutusData.integer(0)))));
        comparisons.add(compare("mutualA", List.of(
                OptimizationBenchmarkRunner.InputCase.of("three", integers(1, 2, 3), PlutusData.integer(3)))));
        comparisons.add(compare("records", List.of(
                input("empty", PlutusData.list()),
                input("record", PlutusData.list(PlutusData.constr(0, PlutusData.integer(7)))),
                input("bad-record", PlutusData.list(bad)),
                input("bad-field", PlutusData.list(PlutusData.constr(0, bad))),
                input("missing-field", PlutusData.list(PlutusData.constr(0))))));
        comparisons.add(compare("aggregate", List.of(
                OptimizationBenchmarkRunner.InputCase.of("accept", integers(1, 2, 3), PlutusData.integer(1), PlutusData.integer(4)),
                OptimizationBenchmarkRunner.InputCase.of("reject", integers(1, 2, 3), PlutusData.integer(1), PlutusData.integer(6)),
                OptimizationBenchmarkRunner.InputCase.of("negative-drop", integers(1, 2, 3), PlutusData.integer(-1), PlutusData.integer(0)),
                OptimizationBenchmarkRunner.InputCase.of("empty-after-drop", integers(1, 2, 3), PlutusData.integer(3), PlutusData.integer(1)),
                OptimizationBenchmarkRunner.InputCase.of("skip-malformed", PlutusData.list(bad, PlutusData.integer(2)), PlutusData.integer(1), PlutusData.integer(0)),
                OptimizationBenchmarkRunner.InputCase.of("visit-malformed", PlutusData.list(PlutusData.integer(2), bad), PlutusData.integer(1), PlutusData.integer(0)))));
        return List.copyOf(comparisons);
    }

    public static OptimizationBenchmarkRunner.Comparison compare(
            String method, List<OptimizationBenchmarkRunner.InputCase> inputs) {
        return OptimizationBenchmarkRunner.compareWithJavaAndTruffle(
                new OptimizationBenchmarkRunner.Fixture("o3-for-each-" + method, sourceFor(method), method, inputs),
                OptimizationLevel.PV11_SAFE,
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
    }

    // Keep each measured artifact independent. In particular, unrelated recursive
    // helpers would otherwise remain strict bindings and distort size/cost evidence.
    static String sourceFor(String method) {
        String header = SOURCE.substring(0, SOURCE.indexOf("    static BigInteger"));
        String record = method.equals("records") ? "    record Item(BigInteger amount) {}\n" : "";
        String helper = method.equals("mutualA") ? methodText("mutualB") : "";
        return header + record + methodText(method) + helper + "}\n";
    }

    /** Extract a fixture method with its known four-space closing brace. */
    private static String methodText(String method) {
        int start = SOURCE.indexOf("    static BigInteger " + method + "(");
        if (start < 0) throw new IllegalArgumentException("Unknown fixture method: " + method);
        int end = SOURCE.indexOf("\n    }\n", start);
        if (end < 0) throw new IllegalStateException("Fixture method is not closed: " + method);
        return SOURCE.substring(start, end + "\n    }\n".length());
    }

    public static PlutusData integers(long... values) {
        var data = new ArrayList<PlutusData>();
        for (long value : values) data.add(PlutusData.integer(value));
        return new PlutusData.ListData(data);
    }

    private static OptimizationBenchmarkRunner.InputCase input(String id, PlutusData data) {
        return OptimizationBenchmarkRunner.InputCase.of(id, data);
    }

    public static void main(String[] args) {
        comparisons().forEach(c -> System.out.println(c.toMarkdown()));
    }
}
