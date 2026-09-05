package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.*;
import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class O3ListCaseLoweringTest {
    static final String SOURCE = """
            import java.math.BigInteger;
            import com.bloxbean.cardano.julc.core.types.JulcList;
            class ListCaseSample {
                static BigInteger sum(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs) { acc = acc.add(x); }
                    return acc;
                }
                static BigInteger first(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs) { acc = x; break; }
                    return acc;
                }
                static BigInteger unused(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs) { acc = acc.add(BigInteger.ONE); }
                    return acc;
                }
                static BigInteger nested(JulcList<JulcList<BigInteger>> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (JulcList<BigInteger> row : xs) {
                        for (BigInteger x : row) { acc = acc.add(x); }
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
            }
            """;

    @Test
    void supportedLevelsAreDeterministicAndBaselineRetainsLegacyTraversal() {
        for (var method : List.of("sum", "first", "unused", "nested", "multi")) {
            for (boolean maps : List.of(false, true)) {
                for (var level : OptimizationLevel.values()) {
                    var result = compile(method, level, maps);
                    assertArrayEquals(bytes(result), bytes(compile(method, level, maps)));
                    assertEquals(level.pv11SafeRulesEnabled(), result.optimizationReport()
                            .appliedRules().contains(UplcGenerator.PV11_CASE_LIST_RULE));
                    if (!level.pv11SafeRulesEnabled()) {
                        assertTrue(countBuiltin(result.program().term(), DefaultFun.NullList) > 0);
                    }
                    if (maps && level.pv11SafeRulesEnabled()) {
                        var cases = new ArrayList<Term>();
                        collectCases(result.program().term(), cases);
                        assertFalse(cases.isEmpty());
                        cases.forEach(c -> assertNotNull(result.sourceMap().lookup(c)));
                    }
                }
            }
        }
        var defaults = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(SOURCE, "sum");
        assertArrayEquals(bytes(defaults), bytes(compile("sum", OptimizationLevel.PV11_SAFE, false)));
        assertTrue(countBuiltin(defaults.program().term(), DefaultFun.NullList) > 0);
        assertEquals(0, countBuiltin(defaults.program().term(), DefaultFun.HeadList));
        assertEquals(0, countBuiltin(defaults.program().term(), DefaultFun.TailList));
    }

    @Test
    void historicalBytesMatchIndependentPreChangeCheckout() throws Exception {
        // Captured from unmodified 8c9f1f63, not from the candidate compiler.
        try (var in = getClass().getResourceAsStream("/optimization/o3-pre-change-bytes.txt")) {
            assertNotNull(in);
            var lines = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            assertEquals(30, lines.size());
            for (var line : lines) {
                var parts = line.split("=", 2);
                var key = parts[0].split(":");
                var result = compile(key[0], OptimizationLevel.valueOf(key[2]), Boolean.parseBoolean(key[1]));
                if (!key[2].equals("PV11_SAFE")) {
                    assertEquals(parts[1], HexFormat.of().formatHex(bytes(result)), parts[0]);
                } else {
                    var oldBytes = HexFormat.of().parseHex(parts[1]);
                    assertTrue(bytes(result).length <= oldBytes.length,
                            parts[0] + ": old=" + oldBytes.length + " new=" + bytes(result).length);
                    var old = com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder.decodeProgram(oldBytes);
                    var input = key[0].equals("nested")
                            ? PlutusData.list(PlutusData.list(PlutusData.integer(3)))
                            : PlutusData.list(PlutusData.integer(3), PlutusData.integer(0));
                    var vm = CompilerTestVm.pv11();
                    for (var xs : List.of(PlutusData.list(), input)) {
                        var before = vm.evaluateWithArgs(old, List.of(xs));
                        var after = vm.evaluateWithArgs(result.program(), List.of(xs));
                        assertSameOutcome(before, after, parts[0]);
                        assertTrue(after.budgetConsumed().cpuSteps() <= before.budgetConsumed().cpuSteps(), parts[0]);
                        assertTrue(after.budgetConsumed().memoryUnits() <= before.budgetConsumed().memoryUnits(), parts[0]);
                        System.out.printf("previous-safe %s %s bytes=%d/%d cpu=%d/%d memory=%d/%d%n",
                                parts[0], xs.equals(input) ? "nonempty" : "empty", oldBytes.length, bytes(result).length,
                                before.budgetConsumed().cpuSteps(), after.budgetConsumed().cpuSteps(),
                                before.budgetConsumed().memoryUnits(), after.budgetConsumed().memoryUnits());
                    }
                }
            }
        }
    }

    @Test
    void unconditionalBreakRetainsSingleProjection() {
        var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.core.types.JulcList;
                class FirstOnly {
                    static BigInteger first(JulcList<BigInteger> xs) {
                        BigInteger acc = BigInteger.ZERO;
                        for (BigInteger x : xs) { acc = x; break; }
                        return acc;
                    }
                }
                """;
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "first");
        assertFalse(result.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_LIST_RULE));
        assertTrue(countBuiltin(result.program().term(), DefaultFun.HeadList) > 0);
    }

    @Test
    void mapAndWhileTraversalDoNotAcquireListCaseRule() {
        var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.core.types.JulcMap;
                import com.bloxbean.cardano.julc.core.types.JulcList;
                class OtherTraversals {
                    static BigInteger map(JulcMap<BigInteger, BigInteger> xs) {
                        BigInteger acc = BigInteger.ZERO;
                        for (var entry : xs) { acc = acc.add(entry.value()); }
                        return acc;
                    }
                    static BigInteger walk(JulcList<BigInteger> xs) {
                        BigInteger acc = BigInteger.ZERO;
                        while (!xs.isEmpty()) { acc = acc.add(xs.head()); xs = xs.tail(); }
                        return acc;
                    }
                }
                """;
        for (var method : List.of("map", "walk")) {
            var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, method);
            assertFalse(result.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_LIST_RULE));
        }
    }

    @Test
    void javaAndScalusPreserveResultsAndMalformedElementFailures() {
        var bad = PlutusData.bytes(new byte[]{1});
        var inputs = List.of(PlutusData.list(), PlutusData.list(PlutusData.integer(7)),
                PlutusData.list(PlutusData.integer(3), PlutusData.integer(-2), PlutusData.integer(0)),
                PlutusData.list(bad), PlutusData.list(PlutusData.integer(7), bad), bad);
        for (var backend : List.of("Java", "Scalus")) {
            var vm = CompilerTestVm.pv11(backend);
            for (boolean maps : List.of(false, true)) {
                for (var method : List.of("sum", "first", "unused", "multi")) {
                    var baseline = compile(method, OptimizationLevel.BASELINE, maps);
                    var safe = compile(method, OptimizationLevel.PV11_SAFE, maps);
                    for (var input : inputs) {
                        // Scalus is deliberately a language-only cross-check, not certified ledger evaluation.
                        var before = vm.evaluateWithArgs(baseline.program(), List.of(input));
                        var after = vm.evaluateWithArgs(safe.program(), List.of(input));
                        assertSameOutcome(before, after, backend + "/" + method + "/" + input);
                        if (maps && backend.equals("Java") && before instanceof EvalResult.Failure f) {
                            var oldLocation = baseline.sourceMap().lookup(f.failedTerm());
                            var newLocation = safe.sourceMap().lookup(((EvalResult.Failure) after).failedTerm());
                            // Legacy synthetic builtin failures may have no mapped term.
                            assertEquals(oldLocation, newLocation, "Failure source attribution must be preserved");
                        }
                    }
                }
            }
            var first = compile("first", OptimizationLevel.PV11_SAFE, false);
            var result = vm.evaluateWithArgs(first.program(), List.of(PlutusData.list(PlutusData.integer(7), bad)));
            assertEquals(Term.const_(Constant.integer(7)), assertInstanceOf(EvalResult.Success.class, result).resultTerm());
            var unused = compile("unused", OptimizationLevel.PV11_SAFE, false);
            assertFalse(vm.evaluateWithArgs(unused.program(), List.of(PlutusData.list(bad))).isSuccess(),
                    "An unused item must still be decoded");
        }
    }

    @Test
    void rawMatchEvaluatesScrutineeOnceAndOnlySelectedBranch() {
        var data = new PirType.DataType();
        var head = new PirTerm.Var("head", data);
        var tail = new PirTerm.Var("tail", new PirType.ListType(data));
        var omegaLam = new PirTerm.Lam("omega", data,
                new PirTerm.App(new PirTerm.Var("omega", data), new PirTerm.Var("omega", data)));
        var divergent = new PirTerm.App(omegaLam, omegaLam);
        for (var backend : List.of("Java", "Scalus")) {
            var vm = CompilerTestVm.pv11(backend);
            for (boolean empty : List.of(false, true)) {
                var raw = new PirTerm.Const(new Constant.ListConst(DefaultUni.DATA,
                        empty ? List.of() : List.of(Constant.data(PlutusData.integer(7)))));
                var observed = new PirTerm.Trace(new PirTerm.Const(Constant.string("scrutinee")), raw);
                var m = new PirTerm.ListMatch(observed, "head", "tail",
                        empty ? new PirTerm.Const(Constant.integer(9)) : divergent,
                        empty ? divergent : new PirTerm.Let("decoded", PirHelpers.wrapDecode(head, new PirType.IntegerType()),
                                new PirTerm.IfThenElse(new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), tail),
                                        new PirTerm.Var("decoded", new PirType.IntegerType()), new PirTerm.Error(data))));
                var result = vm.evaluate(Program.plutusV3(new UplcGenerator().generate(m)),
                        new ExBudget(10_000_000, 100_000));
                var success = assertInstanceOf(EvalResult.Success.class, result, backend);
                assertEquals(Term.const_(Constant.integer(empty ? 9 : 7)), success.resultTerm());
                assertEquals(List.of("scrutinee"), success.traces());
            }
        }
    }

    @Test
    void listMatchBindersScopeOnlyOverConsBranch() {
        var data = new PirType.DataType();
        var h = new PirTerm.Var("h", data);
        var t = new PirTerm.Var("t", new PirType.ListType(data));
        var m = new PirTerm.ListMatch(t, "h", "t", h, new PirTerm.App(h, t));
        assertEquals(Set.of("h", "t"), PirSubstitution.collectFreeVarNames(m));
        var replacement = new PirTerm.Const(Constant.unit());
        var changed = (PirTerm.ListMatch) PirSubstitution.substitute(m, "h", replacement);
        assertEquals(replacement, changed.nilBranch());
        assertEquals(m.consBranch(), changed.consBranch());
        assertEquals(Set.of("t"), PirSubstitution.collectFreeVarNames(changed));
        assertTrue(PirHelpers.containsVarRef(m, "h"));
        assertTrue(PirFormatter.format(m).contains("list-match"));
        assertTrue(PirFormatter.formatPretty(m).contains("list-match"));
        assertThrows(IllegalArgumentException.class, () -> new PirTerm.ListMatch(t, "h", "h", h, h));
        for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE)) {
            var context = CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(level));
            assertThrows(CompilerException.class, () -> new UplcGenerator(context, null).generate(m));
        }
    }

    private static CompileResult compile(String method, OptimizationLevel level, boolean maps) {
        var options = new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(maps);
        if (level.costProfileRequired()) options.setOptimizationCostProfile(
                OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(SOURCE, method);
    }

    private static byte[] bytes(CompileResult result) { return UplcFlatEncoder.encodeProgram(result.program()); }

    private static void assertSameOutcome(EvalResult before, EvalResult after, String label) {
        assertEquals(before.getClass(), after.getClass(), label);
        assertEquals(before.traces(), after.traces(), label);
        if (before instanceof EvalResult.Success s) {
            assertEquals(s.resultTerm(), ((EvalResult.Success) after).resultTerm(), label);
        } else if (before instanceof EvalResult.Failure f) {
            assertEquals(f.error(), ((EvalResult.Failure) after).error(), label);
        } else fail("Unexpected budget exhaustion: " + label);
    }

    private static int countBuiltin(Term term, DefaultFun fun) {
        int count = term instanceof Term.Builtin b && b.fun() == fun ? 1 : 0;
        for (var child : children(term)) count += countBuiltin(child, fun);
        return count;
    }

    private static void collectCases(Term term, List<Term> cases) {
        if (term instanceof Term.Case) cases.add(term);
        children(term).forEach(child -> collectCases(child, cases));
    }

    private static List<Term> children(Term term) {
        return switch (term) {
            case Term.Lam(_, var body) -> List.of(body);
            case Term.Delay(var body) -> List.of(body);
            case Term.Force(var body) -> List.of(body);
            case Term.Apply(var fn, var arg) -> List.of(fn, arg);
            case Term.Constr(_, var fields) -> fields;
            case Term.Case(var xs, var branches) -> {
                var all = new ArrayList<Term>(branches); all.add(xs); yield all;
            }
            default -> List.of();
        };
    }
}
