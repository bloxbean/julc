package org.julclang.compiler.codegen;

import org.julclang.compiler.Adr076Sharing;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.uplc.SharedClosedValues;
import org.julclang.compiler.uplc.UplcOptimizer;
import org.julclang.core.PlutusData;
import org.julclang.ledger.Credential;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-076 D1 (issue #325): binding the methods every handler needs once, around the purpose dispatch, keeps what every
 * purpose computes, and what every run that reaches a handler costs. A run with no handler fails at the dispatch as
 * before, with the same traces, after evaluating the shared bindings, which it pays for. A {@code @MultiValidator} with a {@code @Param}, a static field, a failing and a
 * tracing method of no parameters, a constant method, mutually recursive methods and a method that reads the static is
 * compiled with the rule on and off, with two and three handlers, at both levels where it runs and with source maps on
 * and off, and run on contexts of every purpose, including one with no handler.
 */
class SharedBindingsTest {

    static final String TWO = """
            package probe;
            import java.math.BigInteger;
            import java.util.Optional;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.MultiValidator;
            import org.julclang.stdlib.annotation.Param;
            import org.julclang.stdlib.annotation.Purpose;

            @MultiValidator
            public class Probe {
                @Param static long LIMIT = 5;
                static final BigInteger K = BigInteger.valueOf(7);

                @Entrypoint(purpose = Purpose.MINT)
                public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                    return body(Builtins.unIData(redeemer));
                }

                @Entrypoint(purpose = Purpose.SPEND)
                public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                    BigInteger r = Builtins.unIData(redeemer);
                    if (r.compareTo(BigInteger.valueOf(100)) > 0) return boom().compareTo(BigInteger.ZERO) == 0;
                    if (r.compareTo(BigInteger.valueOf(50)) > 0) return loud().compareTo(BigInteger.ZERO) > 0;
                    return underLimit(r) && even(r) && withK(r) && five().compareTo(BigInteger.ZERO) > 0;
                }

                static boolean body(BigInteger r) {
                    if (r.compareTo(BigInteger.valueOf(100)) > 0) return boom().compareTo(BigInteger.ZERO) == 0;
                    if (r.compareTo(BigInteger.valueOf(50)) > 0) return loud().compareTo(BigInteger.ZERO) > 0;
                    return underLimit(r) && even(r) && withK(r) && five().compareTo(BigInteger.ZERO) > 0;
                }

                static BigInteger boom() { Builtins.error(); return BigInteger.ZERO; }
                static BigInteger loud() { Builtins.trace("loud", Builtins.iData(1)); return BigInteger.ONE; }
                static BigInteger five() { return BigInteger.valueOf(5); }
                static boolean underLimit(BigInteger r) { return r.compareTo(BigInteger.valueOf(LIMIT)) < 0; }
                static boolean even(BigInteger n) { return n.equals(BigInteger.ZERO) ? true : odd(n.subtract(BigInteger.ONE)); }
                static boolean odd(BigInteger n) { return n.equals(BigInteger.ZERO) ? false : even(n.subtract(BigInteger.ONE)); }
                static boolean withK(BigInteger r) { return r.compareTo(K) != 0; }
            }
            """;

    /** A third handler that uses only {@code five} and {@code loud}: every other method is needed by two of three. */
    static final String THREE = TWO.replace("""
                static boolean body(BigInteger r) {""", """
                @Entrypoint(purpose = Purpose.WITHDRAW)
                public static boolean withdraw(PlutusData redeemer, ScriptContext ctx) {
                    return five().compareTo(BigInteger.valueOf(Builtins.unIData(redeemer).longValue())) == 0 || loud().signum() == 0;
                }

                static boolean body(BigInteger r) {""");

    private static final List<OptimizationLevel> LEVELS = List.of(OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED);

    /**
     * The machine steps a run with no handler pays with D1 on: the shared bindings ({@code underLimit} and the
     * {@code even}/{@code odd} group with its fixpoint) are evaluated before the dispatch fails. Nothing is shared with
     * three handlers.
     */
    private static final Map<String, Integer> SHARED_BINDING_STEPS = Map.of("two", 30, "three", 0);

    private static final List<String> METHODS = List.of("body", "boom", "loud", "five", "underLimit", "even", "odd", "withK");

    @Test
    void everyRunKeepsItsOutcomeAndEveryHandledRunItsBudget() {
        int runs = 0;
        for (var source : List.of(TWO, THREE)) {
            for (var level : LEVELS) {
                for (boolean maps : List.of(false, true)) {
                    String label = name(source) + "/" + level + "/" + maps;
                    // Variants by (D1, D2): D1 is compared with D2 held fixed, on and off.
                    var bothOn = compile(source, level, maps, true, true);
                    var d1Only = compile(source, level, maps, true, false);
                    var d2Only = compile(source, level, maps, false, true);
                    var bothOff = compile(source, level, maps, false, false);
                    // D2 is a beta-expansion of the program it runs on, with D1 on or off.
                    assertEquals(d1Only.program().term(), SharedClosedValues.unshare(bothOn.program().term()), label);
                    assertEquals(bothOff.program().term(), SharedClosedValues.unshare(d2Only.program().term()), label);
                    int sharedWithD1 = SharedClosedValues.count(bothOn.program().term());
                    int sharedWithoutD1 = SharedClosedValues.count(d2Only.program().term());
                    for (long limit : List.of(5L, 1_000L)) {
                        for (var context : contexts()) {
                            String run = label + "/limit=" + limit + "/" + context.name();
                            var withBoth = evaluate(bothOn, limit, context);
                            var withD1 = evaluate(d1Only, limit, context);
                            var withD2 = evaluate(d2Only, limit, context);
                            var without = evaluate(bothOff, limit, context);
                            for (var result : List.of(withBoth, withD1, withD2)) assertSameOutcome(without, result, run);
                            assertEquals(context.accepts(name(source), limit), without.isSuccess(), run);
                            boolean sharing = bothOn.optimizationReport().appliedRules().contains(SharedBindings.RULE);
                            if (context.handledBy(name(source))) {
                                // A run that reaches a handler costs what it did: D1 adds nothing and saves nothing.
                                assertEquals(withD2.budgetConsumed(), withBoth.budgetConsumed(), run + ": D1 with D2 on");
                                assertEquals(without.budgetConsumed(), withD1.budgetConsumed(), run + ": D1 with D2 off");
                            } else {
                                // A run with no handler fails at the dispatch, after evaluating the shared bindings.
                                int steps = SHARED_BINDING_STEPS.get(name(source));
                                assertEquals(sharing, steps > 0, run);
                                assertWholeSteps(steps, withD2.budgetConsumed(), withBoth.budgetConsumed(), run + ": D1 with D2 on");
                                assertWholeSteps(steps, without.budgetConsumed(), withD1.budgetConsumed(), run + ": D1 with D2 off");
                            }
                            Adr076Sharing.assertBudgetWithin(withBoth.budgetConsumed(), withD1.budgetConsumed(), sharedWithD1, run);
                            Adr076Sharing.assertBudgetWithin(withD2.budgetConsumed(), without.budgetConsumed(), sharedWithoutD1, run);
                            runs++;
                        }
                    }
                }
            }
        }
        assertEquals(2 * 2 * 2 * 2 * contexts().size(), runs);
    }

    @Test
    void withTwoHandlersOnlyTheFunctionsBothNeedAreBoundOnceAroundTheDispatch() {
        for (var level : LEVELS) {
            for (boolean maps : List.of(false, true)) {
                String label = level + "/" + maps;
                var on = compile(TWO, level, maps, true, false);
                var off = compile(TWO, level, maps, false, false);
                assertTrue(on.optimizationReport().appliedRules().contains(SharedBindings.RULE), label);
                assertFalse(off.optimizationReport().appliedRules().contains(SharedBindings.RULE), label);
                var shared = binders(on.pirTerm());
                var unshared = binders(off.pirTerm());
                for (String method : METHODS) assertEquals(2, unshared.get(method).size(), label + " " + method);
                // underLimit reads the @Param, which is bound outside the dispatch; even and odd are shared together.
                for (String method : List.of("underLimit", "even", "odd")) assertEquals(1, shared.get(method).size(), label + " " + method);
                assertEquals(List.of("rec:even+odd"), shared.get("even"), label);
                assertEquals(List.of("rec:even+odd"), shared.get("odd"), label);
                // withK reads the static K, and body reaches withK; five is a constant the optimizer inlines; boom and
                // loud have no parameters. Each handler keeps its own.
                for (String method : List.of("body", "withK", "five", "boom", "loud")) {
                    assertEquals(2, shared.get(method).size(), label + " " + method);
                }
                // boom and loud are never bound as values that run where they are bound.
                for (String method : List.of("boom", "loud")) {
                    assertEquals(List.of("Lam", "Lam"), shared.get(method), label + " " + method);
                    assertEquals(List.of("Lam", "Lam"), unshared.get(method), label + " " + method);
                }
            }
        }
    }

    @Test
    void withThreeHandlersAMethodTwoOfThemNeedIsNotShared() {
        for (var level : LEVELS) {
            for (boolean maps : List.of(false, true)) {
                String label = level + "/" + maps;
                var on = compile(THREE, level, maps, true, false);
                var off = compile(THREE, level, maps, false, false);
                // Every handler needs only five and loud, which have no parameters: nothing is shared.
                assertFalse(on.optimizationReport().appliedRules().contains(SharedBindings.RULE), label);
                var binders = binders(on.pirTerm());
                for (String method : METHODS) assertEquals(3, binders.get(method).size(), label + " " + method);
                assertEquals(off.pirTerm(), on.pirTerm(), label);
                assertEquals(off.program(), on.program(), label);
            }
        }
    }

    private static String name(String source) {
        return source == TWO ? "two" : "three";
    }

    /** A script context of one purpose with an integer redeemer. */
    private record Context(String purpose, long redeemer, PlutusData data) {
        String name() {
            return purpose + "(" + redeemer + ")";
        }

        /** Whether the probe validator has a handler for this context's purpose. */
        boolean handledBy(String validator) {
            return !purpose.equals("withdraw") || validator.equals("three");
        }

        /** Whether the probe validator accepts this context, worked out from its source. */
        boolean accepts(String validator, long limit) {
            return switch (purpose) {
                case "mint", "spend" -> redeemer <= 100 && (redeemer > 50 || (redeemer < limit && redeemer % 2 == 0 && redeemer != 7));
                default -> validator.equals("three") && redeemer == 5;
            };
        }
    }

    private static List<Context> contexts() {
        var contexts = new ArrayList<Context>();
        var policy = new PolicyId(new byte[28]);
        var reference = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
        var credential = new Credential.PubKeyCredential(new PubKeyHash(new byte[28]));
        for (long redeemer : List.of(0L, 2L, 3L, 4L, 5L, 7L, 8L, 60L, 200L)) {
            var value = PlutusData.integer(redeemer);
            contexts.add(new Context("mint", redeemer, ScriptContextTestBuilder.minting(policy).redeemer(value).buildPlutusData()));
            contexts.add(new Context("spend", redeemer,
                    ScriptContextTestBuilder.spending(reference, PlutusData.integer(1)).redeemer(value).buildPlutusData()));
            contexts.add(new Context("withdraw", redeemer, ScriptContextTestBuilder.rewarding(credential).redeemer(value).buildPlutusData()));
        }
        return contexts;
    }

    private static EvalResult evaluate(CompileResult compiled, long limit, Context context) {
        return CompilerTestVm.pv11().evaluateWithArgs(compiled.program().applyParams(PlutusData.integer(limit)), List.of(context.data()));
    }

    /** {@code actual} costs exactly {@code steps} machine steps more than {@code expected}. */
    private static void assertWholeSteps(int steps, ExBudget expected, ExBudget actual, String label) {
        assertEquals(new ExBudget(expected.cpuSteps() + steps * Adr076Sharing.STEP.cpuSteps(),
                expected.memoryUnits() + steps * Adr076Sharing.STEP.memoryUnits()), actual, label);
    }

    private static void assertSameOutcome(EvalResult expected, EvalResult actual, String label) {
        assertEquals(expected.getClass(), actual.getClass(), label);
        assertEquals(expected.traces(), actual.traces(), label);
        if (expected instanceof EvalResult.Success success) {
            assertEquals(success.resultTerm(), ((EvalResult.Success) actual).resultTerm(), label);
        } else if (expected instanceof EvalResult.Failure failure) {
            assertEquals(failure.error(), ((EvalResult.Failure) actual).error(), label);
        }
    }

    /** A method's binder without its class: {@code probe.Probe.even} is {@code even}. */
    private static String simpleName(String binder) {
        return binder.startsWith("probe.Probe.") ? binder.substring("probe.Probe.".length()) : binder;
    }

    /**
     * Each name a let or a letrec binds in {@code term}, with one entry per binding: the kind of a let's value, or
     * {@code rec:} and the names of the letrec's group.
     */
    private static Map<String, List<String>> binders(PirTerm term) {
        var binders = new TreeMap<String, List<String>>();
        var pending = new ArrayDeque<PirTerm>();
        pending.push(term);
        while (!pending.isEmpty()) {
            var next = pending.pop();
            if (next instanceof PirTerm.Let let) {
                binders.computeIfAbsent(simpleName(let.name()), n -> new ArrayList<>()).add(let.value().getClass().getSimpleName());
            } else if (next instanceof PirTerm.LetRec letRec) {
                var group = "rec:" + String.join("+", letRec.bindings().stream().map(binding -> simpleName(binding.name())).sorted().toList());
                for (var binding : letRec.bindings()) binders.computeIfAbsent(simpleName(binding.name()), n -> new ArrayList<>()).add(group);
            }
            PirHelpers.children(next).forEach(pending::push);
        }
        return binders;
    }

    private static CompileResult compile(String source, OptimizationLevel level, boolean maps, boolean d1, boolean d2) {
        var options = new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(maps);
        if (!d1) options.disableOptimizationRule(SharedBindings.RULE);
        if (!d2) options.disableOptimizationRule(UplcOptimizer.SHARED_CLOSED_VALUES_RULE);
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileWithDetails(source);
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        return result;
    }
}
