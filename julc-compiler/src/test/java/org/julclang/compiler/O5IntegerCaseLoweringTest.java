package org.julclang.compiler;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.uplc.UplcGenerator;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-041 O5: compiler-generated sealed-interface dispatch lowers to one PV11 integer
 * {@code Case} on the decoded constructor tag under the safe profile.
 * <p>
 * Every expectation is checked against the pre-change bytes captured at the base commit
 * ({@code optimization/o5-pre-change-bytes.txt}): NONE/BASELINE stay byte-identical, and the
 * safe profile is observationally equivalent on Java, Truffle and Scalus for every scenario,
 * smaller, and never more expensive on any path. The only tolerated difference is the
 * documented failure text on a malformed constructor tag (see {@link CaseFailureEquivalence}).
 */
@Tag("pair-case-backends")
class O5IntegerCaseLoweringTest {

    record Scenario(String name, PlutusData redeemer, boolean success) {
        @Override public String toString() { return name; }
    }

    private static final BigInteger HUGE_TAG = BigInteger.ONE.shiftLeft(40);

    private static List<List<Scenario>> scenarios() {
        var fast = PlutusData.constr(0);
        var slow = PlutusData.constr(1);
        var manual = PlutusData.constr(2, PlutusData.integer(5));
        return List.of(
                // TWO: Pay(amount) | Cancel
                List.of(new Scenario("pay-positive", PlutusData.constr(0, PlutusData.integer(7)), true),
                        new Scenario("pay-zero", PlutusData.constr(0, PlutusData.integer(0)), false),
                        new Scenario("cancel", PlutusData.constr(1), true),
                        new Scenario("pay-wrong-field-type", PlutusData.constr(0, PlutusData.bytes(new byte[0])), false),
                        new Scenario("cancel-extra-field", PlutusData.constr(1, PlutusData.integer(1)), false)),
                // THREE: Mint(amount) | Burn(amount) | Pause (default arm)
                List.of(new Scenario("mint-positive", PlutusData.constr(0, PlutusData.integer(3)), true),
                        new Scenario("mint-zero", PlutusData.constr(0, PlutusData.integer(0)), false),
                        new Scenario("burn-negative", PlutusData.constr(1, PlutusData.integer(-3)), true),
                        new Scenario("burn-positive", PlutusData.constr(1, PlutusData.integer(3)), false),
                        new Scenario("pause-default-arm", PlutusData.constr(2), false)),
                // FIVE: A | B | C | D | E, alternating results, first and last branch covered
                List.of(new Scenario("A-first", PlutusData.constr(0), true),
                        new Scenario("B", PlutusData.constr(1), false),
                        new Scenario("C", PlutusData.constr(2), true),
                        new Scenario("D", PlutusData.constr(3), false),
                        new Scenario("E-last", PlutusData.constr(4), true),
                        new Scenario("tag-5-just-out-of-range", PlutusData.constr(5), false)),
                // NESTED: Pay(amount, mode) | Cancel, mode = Fast | Slow | Manual(limit)
                List.of(new Scenario("pay-fast-small", PlutusData.constr(0, PlutusData.integer(3), fast), true),
                        new Scenario("pay-fast-large", PlutusData.constr(0, PlutusData.integer(30), fast), false),
                        new Scenario("pay-slow", PlutusData.constr(0, PlutusData.integer(30), slow), true),
                        new Scenario("pay-manual-within", PlutusData.constr(0, PlutusData.integer(5), manual), true),
                        new Scenario("pay-manual-over", PlutusData.constr(0, PlutusData.integer(6), manual), false),
                        new Scenario("pay-inner-tag-3", PlutusData.constr(0, PlutusData.integer(1), PlutusData.constr(3)), false),
                        new Scenario("cancel", PlutusData.constr(1), true)),
                // SINGLE: Only(value)
                List.of(new Scenario("only-positive", PlutusData.constr(0, PlutusData.integer(7)), true),
                        new Scenario("only-zero", PlutusData.constr(0, PlutusData.integer(0)), false)));
    }

    /** Scenarios every fixture gets: non-constructor, tag n, out-of-range and huge tags, missing fields. */
    private static List<Scenario> commonInvalid(int constructors) {
        return List.of(new Scenario("not-a-constructor", PlutusData.integer(0), false),
                new Scenario("tag-n", PlutusData.constr(constructors), false),
                new Scenario("tag-99", PlutusData.constr(99), false),
                new Scenario("tag-2^40", new PlutusData.ConstrData(HUGE_TAG, List.of()), false),
                new Scenario("tag-0-no-fields", PlutusData.constr(0), false));
    }

    @Test
    void historicalBytesAndDispatchEquivalenceAcrossBackends() throws IOException {
        var perFixture = scenarios();
        for (int i = 0; i < O5IntegerCaseFixtures.SOURCES.size(); i++) {
            var source = O5IntegerCaseFixtures.SOURCES.get(i);
            int constructors = O5IntegerCaseFixtures.OUTER_CONSTRUCTORS.get(i);
            var inputs = new ArrayList<>(perFixture.get(i));
            for (var common : commonInvalid(constructors)) {
                // FIVE's zero-field A is a valid "tag-0-no-fields"; the fixture list covers it.
                if (!(i == 2 && common.name().equals("tag-0-no-fields"))) inputs.add(common);
            }
            for (var level : OptimizationLevel.values()) {
                for (boolean maps : List.of(false, true)) {
                    var compiled = PairCaseLoweringTest.compile(source, level, maps);
                    assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
                    var program = compiled.program();
                    var bytes = UplcFlatEncoder.encodeProgram(program);
                    // Deterministic bytes for identical inputs.
                    assertArrayEquals(bytes, UplcFlatEncoder.encodeProgram(
                            PairCaseLoweringTest.compile(source, level, maps).program()));
                    var old = golden(i, level.pv11SafeRulesEnabled() ? OptimizationLevel.PV11_SAFE : level, maps);
                    boolean dispatches = constructors >= 2;
                    assertEquals(level.pv11SafeRulesEnabled() && dispatches,
                            compiled.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_INTEGER_RULE),
                            i + "/" + level);
                    if (!level.pv11SafeRulesEnabled() || !dispatches) {
                        assertArrayEquals(UplcFlatEncoder.encodeProgram(old), bytes, i + "/" + level + "/" + maps);
                    } else {
                        assertTrue(bytes.length < UplcFlatEncoder.encodeProgram(old).length, i + "/" + maps);
                        assertNotEquals(JulcScriptAdapter.scriptHash(old), JulcScriptAdapter.scriptHash(program));
                    }
                    for (var input : inputs) {
                        EvalResult javaResult = null;
                        for (String provider : List.of("Java", "Truffle", "Scalus")) {
                            var before = evaluate(old, input.redeemer(), provider);
                            var after = evaluate(program, input.redeemer(), provider);
                            String label = i + "/" + level + "/" + maps + "/" + provider + "/" + input;
                            assertEquals(input.success(), after.isSuccess(), label);
                            assertEquals(before.getClass(), after.getClass(), label);
                            assertEquals(before.traces(), after.traces(), label);
                            if (before instanceof EvalResult.Success b && after instanceof EvalResult.Success a) {
                                assertEquals(b.resultTerm(), a.resultTerm(), label);
                            } else if (before instanceof EvalResult.Failure b && after instanceof EvalResult.Failure a) {
                                if (input.redeemer() instanceof PlutusData.ConstrData c) {
                                    CaseFailureEquivalence.assertFailureTextEquivalent(b, a, c.constructorTag(), constructors, label);
                                } else {
                                    CaseFailureEquivalence.assertFailureTextEquivalent(b, a, label);
                                }
                            }
                            if (provider.equals("Java")) javaResult = after;
                            if (provider.equals("Truffle")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), label);
                            if (level.pv11SafeRulesEnabled() && !provider.equals("Scalus")) {
                                assertTrue(after.budgetConsumed().cpuSteps() <= before.budgetConsumed().cpuSteps(), label);
                                assertTrue(after.budgetConsumed().memoryUnits() <= before.budgetConsumed().memoryUnits(), label);
                            }
                            if (level == OptimizationLevel.PV11_SAFE && !maps && provider.equals("Java")) {
                                System.out.println("INTEGER_CASE_COST " + i + " " + input + " "
                                        + before.budgetConsumed() + " -> " + after.budgetConsumed());
                            }
                        }
                    }
                    if (level == OptimizationLevel.PV11_SAFE && !maps) {
                        System.out.println("INTEGER_CASE_ARTIFACT " + i + " " + UplcFlatEncoder.encodeProgram(old).length
                                + " -> " + bytes.length + " " + JulcScriptAdapter.scriptHash(old)
                                + " -> " + JulcScriptAdapter.scriptHash(program));
                    }
                }
            }
        }
    }

    @Test
    void safeProfileDispatchIsOneIntegerCasePerSwitchAndNoTagComparisons() {
        // Match sites (lam __match_fields <body>): TWO 1, THREE 1, FIVE 1, NESTED 2 (outer + inner),
        // SINGLE 1. A single constructor binds its fields but never dispatches.
        var expectedSites = List.of(1, 1, 1, 2, 1);
        var expectedBranches = List.of(List.of(2), List.of(3), List.of(5), List.of(2, 3), List.<Integer>of());
        for (int i = 0; i < O5IntegerCaseFixtures.SOURCES.size(); i++) {
            boolean dispatches = O5IntegerCaseFixtures.OUTER_CONSTRUCTORS.get(i) >= 2;
            var safe = PairCaseLoweringTest.compile(O5IntegerCaseFixtures.SOURCES.get(i), OptimizationLevel.PV11_SAFE, false);
            var baseline = PairCaseLoweringTest.compile(O5IntegerCaseFixtures.SOURCES.get(i), OptimizationLevel.BASELINE, false);
            var safeSites = dispatchBodies(safe.program().term());
            var baselineSites = dispatchBodies(baseline.program().term());
            assertEquals(expectedSites.get(i), safeSites.size(), "fixture " + i);
            assertEquals(expectedSites.get(i), baselineSites.size(), "fixture " + i + " baseline");
            var branchCounts = new ArrayList<Integer>();
            for (int s = 0; s < safeSites.size(); s++) {
                var site = safeSites.get(s);
                if (!dispatches) {
                    // Single constructor: field extraction only, never a Case on the tag binder.
                    assertFalse(site instanceof Term.Case c && c.scrutinee() instanceof Term.Var, "fixture " + i);
                    continue;
                }
                // One integer Case on the tag binder (de Bruijn index 2 under the fields binder)
                // with argument-free branches.
                var dispatch = assertInstanceOf(Term.Case.class, site, "fixture " + i);
                var scrutinee = assertInstanceOf(Term.Var.class, dispatch.scrutinee(), "fixture " + i);
                assertEquals(2, scrutinee.name().index(), "fixture " + i);
                assertTrue(dispatch.branches().stream().noneMatch(b -> b instanceof Term.Lam), "fixture " + i);
                branchCounts.add(dispatch.branches().size());
            }
            assertEquals(expectedBranches.get(i), branchCounts.stream().sorted().toList(), "fixture " + i);
            // The safe program keeps exactly the branch bodies' comparisons: the chain's one
            // EqualsInteger per constructor is gone at every dispatch site, nested ones included.
            int removed = branchCounts.stream().mapToInt(Integer::intValue).sum();
            assertEquals(countBuiltin(baseline.program().term(), DefaultFun.EqualsInteger) - removed,
                    countBuiltin(safe.program().term(), DefaultFun.EqualsInteger), "fixture " + i);
            for (var site : baselineSites) {
                assertFalse(site instanceof Term.Case, "fixture " + i + " baseline");
                // The historical chain compares the tag at least once per constructor.
                if (dispatches) {
                    assertTrue(countBuiltin(site, DefaultFun.EqualsInteger) >= 2, "fixture " + i + " baseline");
                }
            }
        }
    }

    @Test
    void onlySelectedBranchRunsAndTracesMatchTheChain() {
        // Each arm traces before producing its result; one arm fails. Under both profiles the
        // trace list must contain exactly the selected arm's trace, so unselected arms
        // (including the failing one) are never evaluated and trace order is preserved.
        String source = """
                import java.math.BigInteger;
                import org.julclang.stdlib.lib.ContextsLib;
                import org.julclang.stdlib.Builtins;
                @MintingValidator class TracedWay {
                    sealed interface Step permits A, B, C, D {}
                    record A() implements Step {}
                    record B(BigInteger n) implements Step {}
                    record C() implements Step {}
                    record D() implements Step {}
                    @Entrypoint static boolean validate(Step s, ScriptContext ctx) {
                        return switch (s) {
                            case A a -> { ContextsLib.trace("arm-a"); yield true; }
                            case B b -> { ContextsLib.trace("arm-b"); yield b.n().signum() > 0; }
                            case C c -> { ContextsLib.trace("arm-c"); Builtins.error(); yield false; }
                            case D d -> { ContextsLib.trace("arm-d"); yield true; }
                        };
                    }
                }
                """;
        var baseline = PairCaseLoweringTest.compile(source, OptimizationLevel.BASELINE, false);
        var safe = PairCaseLoweringTest.compile(source, OptimizationLevel.PV11_SAFE, false);
        assertFalse(baseline.hasErrors(), baseline.diagnostics().toString());
        assertFalse(safe.hasErrors(), safe.diagnostics().toString());
        assertTrue(safe.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_INTEGER_RULE));
        var expectations = List.of(
                new Scenario("A", PlutusData.constr(0), true),
                new Scenario("B-positive", PlutusData.constr(1, PlutusData.integer(5)), true),
                new Scenario("B-zero", PlutusData.constr(1, PlutusData.integer(0)), false),
                new Scenario("C-fails-after-trace", PlutusData.constr(2), false),
                new Scenario("D-last", PlutusData.constr(3), true));
        var expectedTrace = List.of("arm-a", "arm-b", "arm-b", "arm-c", "arm-d");
        for (int s = 0; s < expectations.size(); s++) {
            var scenario = expectations.get(s);
            for (String provider : List.of("Java", "Truffle", "Scalus")) {
                var before = evaluate(baseline.program(), scenario.redeemer(), provider);
                var after = evaluate(safe.program(), scenario.redeemer(), provider);
                String label = provider + "/" + scenario;
                assertEquals(scenario.success(), after.isSuccess(), label);
                assertEquals(before.getClass(), after.getClass(), label);
                assertEquals(List.of(expectedTrace.get(s)), after.traces(), label);
                assertEquals(before.traces(), after.traces(), label);
                if (before instanceof EvalResult.Success b && after instanceof EvalResult.Success a) {
                    assertEquals(b.resultTerm(), a.resultTerm(), label);
                }
            }
        }
    }

    @Test
    void hugeAndNegativeTagsFailAtSelectionWithoutRunningAnyBranch() {
        // Direct term: the compiled dispatch shape applied to raw tags beyond the int range.
        // Every branch traces, so an empty trace list proves no branch ran.
        var dispatch = Term.lam("tag", new Term.Case(Term.var(1), List.of(
                tracedInteger("b0", 10), tracedInteger("b1", 20), tracedInteger("b2", 30))));
        for (String provider : List.of("Java", "Truffle", "Scalus")) {
            var vm = CompilerTestVm.pv11(provider);
            for (var tag : List.of(BigInteger.valueOf(-1), BigInteger.valueOf(3), HUGE_TAG,
                    BigInteger.ONE.shiftLeft(64), BigInteger.ONE.shiftLeft(64).negate())) {
                var program = Program.plutusV3(Term.apply(dispatch, Term.const_(Constant.integer(tag))));
                var result = provider.equals("Scalus") ? vm.evaluate(program)
                        : vm.evaluate(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), null, EvalOptions.DEFAULT);
                var failure = assertInstanceOf(EvalResult.Failure.class, result, provider + "/" + tag);
                CaseFailureEquivalence.assertOutOfRangeText(failure.error(), tag, 3, provider + "/" + tag);
                assertEquals(List.of(), failure.traces(), provider + "/" + tag);
            }
            for (int tag = 0; tag < 3; tag++) {
                var program = Program.plutusV3(Term.apply(dispatch, Term.const_(Constant.integer(tag))));
                var result = provider.equals("Scalus") ? vm.evaluate(program)
                        : vm.evaluate(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), null, EvalOptions.DEFAULT);
                var success = assertInstanceOf(EvalResult.Success.class, result, provider + "/" + tag);
                assertEquals(Term.const_(Constant.integer(10L * (tag + 1))), success.resultTerm());
                assertEquals(List.of("b" + tag), success.traces(), provider + "/" + tag);
            }
        }
    }

    private static Term tracedInteger(String message, long value) {
        return Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.Trace)),
                Term.const_(Constant.string(message))), Term.const_(Constant.integer(value)));
    }

    @Test
    void compileMethodPathReachesDispatchAndPinsFailureTextForEveryOutOfRangeTag() {
        // compileMethod (and therefore the testkit's JulcEval/MethodEvaluator) decodes sealed
        // parameters without the strict boundary, so out-of-range tags reach the dispatch. This
        // is the one compiled-Java path where the ADR-041 failure text is observable. Distinct
        // results per arm also make any branch permutation detectable.
        String source = """
                import java.math.BigInteger;
                class Distinct {
                    sealed interface Step permits A, B, C, D, E {}
                    record A() implements Step {}
                    record B() implements Step {}
                    record C() implements Step {}
                    record D() implements Step {}
                    record E() implements Step {}
                    static BigInteger which(Step s) {
                        return switch (s) {
                            case A a -> BigInteger.ONE;
                            case B b -> BigInteger.TWO;
                            case C c -> BigInteger.valueOf(3);
                            case D d -> BigInteger.valueOf(4);
                            case E e -> BigInteger.valueOf(5);
                        };
                    }
                }
                """;
        var baseline = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE)).compileMethod(source, "which");
        var safe = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE)).compileMethod(source, "which");
        assertFalse(baseline.hasErrors(), baseline.diagnostics().toString());
        assertFalse(safe.hasErrors(), safe.diagnostics().toString());
        assertTrue(safe.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_INTEGER_RULE));
        assertFalse(baseline.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_INTEGER_RULE));
        for (String provider : List.of("Java", "Truffle", "Scalus")) {
            var vm = CompilerTestVm.pv11(provider);
            for (int tag = 0; tag < 5; tag++) {
                for (var compiled : List.of(baseline, safe)) {
                    var result = evaluateWith(vm, provider, compiled.program(), PlutusData.constr(tag));
                    assertEquals(Term.const_(Constant.integer(tag + 1)),
                            assertInstanceOf(EvalResult.Success.class, result, provider + "/" + tag).resultTerm());
                }
            }
            // Scalus cannot deserialize a constructor tag beyond the unsigned 64-bit domain, so the
            // constructor-input matrix tops out at 2^40; the raw-term test above covers 2^64.
            for (var tag : List.of(BigInteger.valueOf(5), BigInteger.valueOf(99), HUGE_TAG)) {
                var redeemer = new PlutusData.ConstrData(tag, List.of());
                var before = assertInstanceOf(EvalResult.Failure.class,
                        evaluateWith(vm, provider, baseline.program(), redeemer), provider + "/" + tag);
                var after = assertInstanceOf(EvalResult.Failure.class,
                        evaluateWith(vm, provider, safe.program(), redeemer), provider + "/" + tag);
                assertEquals(CaseFailureEquivalence.legacyErrorText(provider), before.error(), provider + "/" + tag);
                CaseFailureEquivalence.assertOutOfRangeText(after.error(), tag, 5, provider + "/" + tag);
                CaseFailureEquivalence.assertFailureTextEquivalent(before, after, tag, 5, provider + "/" + tag);
                assertEquals(List.of(), after.traces(), provider + "/" + tag);
            }
        }
    }

    @Test
    void producerGuardsRejectTooFewBranchesAndNonSafeProfiles() {
        assertThrows(IllegalArgumentException.class, () -> new PirTerm.IntegerCase(
                new PirTerm.Var("tag", new PirType.IntegerType()), List.of(new PirTerm.Const(Constant.integer(1)))));
        var term = new PirTerm.IntegerCase(new PirTerm.Var("tag", new PirType.IntegerType()),
                List.of(new PirTerm.Const(Constant.integer(1)), new PirTerm.Const(Constant.integer(2))));
        var wrapped = new PirTerm.Let("tag", new PirTerm.Const(Constant.integer(0)), term);
        for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE)) {
            var context = CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(level));
            assertThrows(CompilerException.class, () -> new UplcGenerator(context, null).generate(wrapped), level.toString());
        }
        var safeContext = CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE));
        var generated = new UplcGenerator(safeContext, null).generate(wrapped);
        assertTrue(generated.toString().contains("Case"), generated.toString());
    }

    private static EvalResult evaluateWith(JulcVm vm, String provider, Program program, PlutusData arg) {
        return provider.equals("Scalus") ? vm.evaluateWithArgs(program, List.of(arg))
                : vm.evaluateWithArgs(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), List.of(arg), null, EvalOptions.DEFAULT);
    }

    /**
     * Bodies of every {@code (lam __match_fields body)}, in traversal order. Under the pair
     * Case the binder sits directly under {@code __match_tag}; under the legacy expansion it
     * is the applied lambda of the {@code Let}. Either way its body is the dispatch.
     */
    private static List<Term> dispatchBodies(Term term) {
        var found = new ArrayList<Term>();
        collectDispatchBodies(term, found);
        return found;
    }

    private static void collectDispatchBodies(Term term, List<Term> found) {
        switch (term) {
            case Term.Lam fields when fields.paramName().equals("__match_fields") -> {
                found.add(fields.body());
                collectDispatchBodies(fields.body(), found);
            }
            case Term.Lam l -> collectDispatchBodies(l.body(), found);
            case Term.Case c -> {
                collectDispatchBodies(c.scrutinee(), found);
                c.branches().forEach(b -> collectDispatchBodies(b, found));
            }
            case Term.Apply a -> { collectDispatchBodies(a.function(), found); collectDispatchBodies(a.argument(), found); }
            case Term.Force f -> collectDispatchBodies(f.term(), found);
            case Term.Delay d -> collectDispatchBodies(d.term(), found);
            case Term.Constr c -> c.fields().forEach(f -> collectDispatchBodies(f, found));
            default -> { }
        }
    }

    private static int countBuiltin(Term term, DefaultFun fun) {
        return switch (term) {
            case Term.Builtin b -> b.fun() == fun ? 1 : 0;
            case Term.Apply a -> countBuiltin(a.function(), fun) + countBuiltin(a.argument(), fun);
            case Term.Lam l -> countBuiltin(l.body(), fun);
            case Term.Force f -> countBuiltin(f.term(), fun);
            case Term.Delay d -> countBuiltin(d.term(), fun);
            case Term.Case c -> countBuiltin(c.scrutinee(), fun)
                    + c.branches().stream().mapToInt(b -> countBuiltin(b, fun)).sum();
            case Term.Constr c -> c.fields().stream().mapToInt(f -> countBuiltin(f, fun)).sum();
            default -> 0;
        };
    }

    private static EvalResult evaluate(Program program, PlutusData redeemer, String provider) {
        var vm = CompilerTestVm.pv11(provider);
        var context = PlutusData.constr(0, PlutusData.integer(0), redeemer,
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        return provider.equals("Scalus") ? vm.evaluateWithArgs(program, List.of(context))
                : vm.evaluateWithArgs(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), List.of(context), null, EvalOptions.DEFAULT);
    }

    static Program golden(int fixture, OptimizationLevel level, boolean maps) throws IOException {
        String id = fixture + "-" + level + "-" + maps;
        try (var input = O5IntegerCaseLoweringTest.class.getResourceAsStream("/optimization/o5-pre-change-bytes.txt")) {
            assertNotNull(input);
            String hex = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(id + " ")).findFirst()
                    .orElseThrow(() -> new AssertionError("missing golden row " + id
                            + " in optimization/o5-pre-change-bytes.txt; recapture from the base commit"))
                    .substring(id.length() + 1);
            return UplcFlatDecoder.decodeProgram(HexFormat.of().parseHex(hex));
        }
    }
}
