package org.julclang.compiler.codegen;

import org.julclang.compiler.Adr076Sharing;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.uplc.InlinedMethods;
import org.julclang.compiler.uplc.SharedClosedValues;
import org.julclang.compiler.uplc.UplcOptimizer;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-076 D1 (issue #325): binding the methods every handler needs once, around the purpose dispatch, keeps what every
 * purpose computes. On the fixtures here it also keeps what every run that reaches a handler costs; a run with no handler
 * fails at the dispatch as before, with the same traces, after evaluating the shared bindings, which it pays for. Helpers
 * whose PIR shape differs from what the optimizer leaves of them are pinned separately. A {@code @MultiValidator} with a {@code @Param}, a static field, a failing and a
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

    private static final String EDGE_HEAD = """
            package probe;
            import java.math.BigInteger;
            import java.util.Optional;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.MultiValidator;
            import org.julclang.stdlib.annotation.Purpose;

            @MultiValidator
            public class Probe {
            """;

    /**
     * A helper whose PIR body renames its parameter before a builtin: the optimizer beta-reduces the {@code let} and
     * eta-reduces {@code \d. unIData d} to the builtin, so D1 reads through the rename and does not share it.
     */
    static final String EDGE_LET_ETA = EDGE_HEAD + """
                @Entrypoint(purpose = Purpose.MINT)
                public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                    return toInt(redeemer).compareTo(BigInteger.ONE) > 0;
                }
                @Entrypoint(purpose = Purpose.SPEND)
                public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                    return toInt(redeemer).compareTo(BigInteger.TWO) > 0;
                }
                static BigInteger toInt(PlutusData d) { PlutusData e = d; return Builtins.unIData(e); }
            }
            """;

    /** The same through a local alias of a parameter of two, called with constants the optimizer folds. */
    static final String EDGE_ALIAS_ADD = EDGE_HEAD + """
                @Entrypoint(purpose = Purpose.MINT)
                public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                    return add(BigInteger.ONE, BigInteger.ONE).equals(BigInteger.TWO);
                }
                @Entrypoint(purpose = Purpose.SPEND)
                public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                    return add(BigInteger.ONE, BigInteger.ONE).equals(BigInteger.TWO);
                }
                static BigInteger add(BigInteger a, BigInteger b) { BigInteger c = a; return c.add(b); }
            }
            """;

    /** Codex round 3: an unused constant local before a builtin; the optimizer drops it and eta-reduces to the builtin. */
    static final String EDGE_UNUSED_LOCAL = EDGE_HEAD + """
                @Entrypoint(purpose = Purpose.MINT)
                public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                    return add(BigInteger.ONE, BigInteger.ONE).equals(BigInteger.TWO);
                }
                @Entrypoint(purpose = Purpose.SPEND)
                public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                    return add(BigInteger.ONE, BigInteger.ONE).equals(BigInteger.TWO);
                }
                static BigInteger add(BigInteger a, BigInteger b) { BigInteger unused = BigInteger.ZERO; return a.add(b); }
            }
            """;

    /**
     * Codex round 4: a cast {@code NarrowingElisionPass} removes, since {@code indexByteString} returns 0 to 255. Alone,
     * the PIR still converts; through the backend {@code at} is the builtin, which each handler inlines.
     */
    static final String EDGE_NARROWED = EDGE_HEAD + """
                @Entrypoint(purpose = Purpose.MINT)
                public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                    return at(Builtins.serialiseData(redeemer), 0) == 3;
                }
                @Entrypoint(purpose = Purpose.SPEND)
                public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                    return at(Builtins.serialiseData(redeemer), 0) == 20;
                }
                static long at(byte[] bytes, long index) { return (int) Builtins.indexByteString(bytes, index); }
            }
            """;

    private static final String EDGE_HEAD_WITH_ARRAYS = EDGE_HEAD.replace("import org.julclang.core.PlutusData;",
            "import org.julclang.core.PlutusData;\nimport org.julclang.core.types.JulcArray;");

    /**
     * Codex round 5: {@code table} is bound in each handler's copy of {@code plusOne}, so the array literal fold sees the
     * name bound twice and leaves it; one copy, bound around the dispatch, would fold to {@code addInteger 1}. D1 runs on
     * the optimizer's output, so the handlers keep what they compiled to. {@code local} reads a static and stays in each
     * handler.
     */
    static final String EDGE_ARRAY = EDGE_HEAD_WITH_ARRAYS + """
                static final BigInteger K = BigInteger.valueOf(50);
                @Entrypoint(purpose = Purpose.MINT)
                public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger r = Builtins.unIData(redeemer);
                    if (r.signum() == 0) return local(r);
                    return r.signum() < 0 && plusOne(r).signum() <= 0;
                }
                @Entrypoint(purpose = Purpose.SPEND)
                public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                    BigInteger r = Builtins.unIData(redeemer);
                    if (r.signum() == 0) return local(r);
                    return r.signum() < 0 && plusOne(r).signum() <= 0;
                }
                static boolean local(BigInteger x) { return x.multiply(x).compareTo(K) < 0; }
                static BigInteger plusOne(BigInteger x) {
                    JulcArray<BigInteger> table = JulcArray.of(BigInteger.ONE);
                    return table.get(0).add(x);
                }
            }
            """;

    /**
     * Codex round 5: six {@code nullList} checks in a helper both handlers call three times. Closed-value sharing binds
     * the forced builtin once only if it occurs often enough; D1 runs after it, so its choice is the one without D1.
     */
    static final String EDGE_NULL_LIST = EDGE_HEAD + """
                @Entrypoint(purpose = Purpose.MINT)
                public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                    return empty(redeemer) && empty(redeemer) && empty(redeemer);
                }
                @Entrypoint(purpose = Purpose.SPEND)
                public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                    return empty(redeemer) && empty(redeemer) && empty(redeemer);
                }
                static boolean empty(PlutusData d) {
                    PlutusData.ListData xs = Builtins.unListData(d);
                    return Builtins.nullList(xs) && Builtins.nullList(xs) && Builtins.nullList(xs)
                            && Builtins.nullList(xs) && Builtins.nullList(xs) && Builtins.nullList(xs);
                }
            }
            """;

    /**
     * Codex round 5: mint and withdraw take an integer redeemer and share {@code square}. A mint run with a bytes
     * redeemer fails at mint's boundary check, before mint's own bindings; with D1 it evaluates the shared binding first.
     */
    static final String BOUNDARY = EDGE_HEAD + """
                @Entrypoint(purpose = Purpose.MINT)
                public static boolean mint(BigInteger redeemer, ScriptContext ctx) {
                    return square(redeemer).compareTo(BigInteger.TEN) > 0;
                }
                @Entrypoint(purpose = Purpose.WITHDRAW)
                public static boolean withdraw(BigInteger redeemer, ScriptContext ctx) {
                    return square(redeemer).compareTo(BigInteger.TWO) > 0;
                }
                static BigInteger square(BigInteger x) { return x.multiply(x).add(x); }
            }
            """;

    /** A helper mint reaches only under {@code if (never())}, a use constant folding removes from that handler. */
    static final String EDGE_DEAD = EDGE_HEAD + """
                @Entrypoint(purpose = Purpose.MINT)
                public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger r = Builtins.unIData(redeemer);
                    if (never()) return m(r, r);
                    return r.signum() > 0;
                }
                @Entrypoint(purpose = Purpose.SPEND)
                public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                    BigInteger r = Builtins.unIData(redeemer);
                    return m(r, r) && m(r, BigInteger.ONE);
                }
                static boolean never() { return false; }
                static boolean m(BigInteger a, BigInteger b) { return a.add(b).compareTo(BigInteger.TEN) > 0 && a.signum() >= 0; }
            }
            """;

    private static final List<OptimizationLevel> LEVELS = List.of(OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED);

    /**
     * The machine steps a run with no handler pays with D1 on: the shared binding of {@code underLimit} is evaluated
     * before the dispatch fails. Nothing is shared with three handlers.
     */
    private static final Map<String, Integer> SHARED_BINDING_STEPS = Map.of("two", 15, "three", 0);

    /** The methods of {@link #TWO} bound once around the dispatch. */
    private static final List<String> SHARED_IN_TWO = List.of("underLimit", "odd");

    /** The steps a run failing at {@link #BOUNDARY}'s boundary check pays for the shared {@code square}. */
    private static final int BOUNDARY_SHARED_BINDING_STEPS = 3;

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
                    // D1 only moves method bindings: with every one inlined, the programs are the same term.
                    assertSameInlined(bothOff.program().term(), d1Only.program().term(), label);
                    assertSameInlined(d2Only.program().term(), bothOn.program().term(), label);
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
                            assertEquals(sharing, d1Only.optimizationReport().appliedRules().contains(SharedBindings.RULE), run);
                            if (context.handledBy(name(source))) {
                                // A run that reaches a handler costs what it did: D1 adds nothing and saves nothing.
                                assertEquals(withD2.budgetConsumed(), withBoth.budgetConsumed(), run + ": D1 with D2 on");
                                assertEquals(without.budgetConsumed(), withD1.budgetConsumed(), run + ": D1 with D2 off");
                            } else {
                                // A run with no handler fails at the dispatch, after evaluating the shared bindings.
                                int steps = maps ? 0 : SHARED_BINDING_STEPS.get(name(source));
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
                assertFalse(off.optimizationReport().appliedRules().contains(SharedBindings.RULE), label);
                // D1 rewrites the optimizer's output: the PIR is what it is without D1.
                assertEquals(off.pirTerm(), on.pirTerm(), label);
                if (maps) {
                    // With source maps the optimizer does not run, and neither does D1.
                    assertFalse(on.optimizationReport().appliedRules().contains(SharedBindings.RULE), label);
                    assertEquals(off.program(), on.program(), label);
                    continue;
                }
                assertTrue(on.optimizationReport().appliedRules().contains(SharedBindings.RULE), label);
                var shared = uplcBindings(on.program().term());
                var unshared = uplcBindings(off.program().term());
                // underLimit reads the @Param, bound outside the dispatch; odd, of the mutually recursive pair, is bound
                // with the copy of even that Bekic's lowering nests in it. Each is bound once instead of once per handler.
                for (String method : SHARED_IN_TWO) {
                    assertEquals(2, unshared.getOrDefault(method, 0), label + " " + method);
                    assertEquals(1, shared.getOrDefault(method, 0), label + " " + method);
                }
                // withK reads the static K, and body reaches withK; five is a constant the optimizer inlines; boom and
                // loud have no parameters. Whatever each handler binds of them, it still binds.
                for (String method : List.of("body", "withK", "five", "boom", "loud")) {
                    assertEquals(unshared.getOrDefault(method, 0), shared.getOrDefault(method, 0), label + " " + method);
                }
                // even's own bindings stay in each handler, less the one that moved inside odd's shared value.
                assertEquals(unshared.get("even") - 1, shared.get("even"), label);
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

    /**
     * Helpers the optimizer treats differently from their PIR shape, and the review's budget scenarios. D1 runs on the
     * optimizer's output, so each handler's code is what it is without D1, and a method is shared only where every
     * handler binds the same function:
     * <ul>
     *   <li>{@link #EDGE_LET_ETA}, {@link #EDGE_ALIAS_ADD}, {@link #EDGE_UNUSED_LOCAL} (Codex round 3) and
     *       {@link #EDGE_NARROWED} (round 4): each handler inlines the method, a builtin once optimized; nothing is
     *       bound to share.</li>
     *   <li>{@link #EDGE_DEAD}: mint's only use of {@code m} folds away under {@code if (never())}, but both handlers
     *       keep the same binding: shared.</li>
     *   <li>{@link #EDGE_ARRAY} (round 5): {@code plusOne} stays as each handler compiled it.</li>
     *   <li>{@link #EDGE_NULL_LIST} (round 5): closed-value sharing decides before D1, on the counts without it.</li>
     * </ul>
     * Every run of every program costs exactly what it does without D1, at both levels and with D2 on or off; with
     * every method binding inlined, the programs are the same term. With source maps D1 does not run.
     */
    @Test
    void edgeProgramsKeepEveryHandledRunsBudgetExactly() {
        record Pin(String source, boolean shares, int bytes, int bytesWithoutD2, List<Context> contexts) {}
        var pins = new LinkedHashMap<String, Pin>();
        pins.put("let-eta", new Pin(EDGE_LET_ETA, false, 0, 0, edgeContexts()));
        pins.put("alias-add", new Pin(EDGE_ALIAS_ADD, false, 0, 0, edgeContexts()));
        pins.put("unused-local", new Pin(EDGE_UNUSED_LOCAL, false, 0, 0, edgeContexts()));
        pins.put("narrowed", new Pin(EDGE_NARROWED, false, 0, 0, edgeContexts()));
        pins.put("dead", new Pin(EDGE_DEAD, true, -39, -39, edgeContexts()));
        pins.put("array", new Pin(EDGE_ARRAY, true, -19, -19, edgeContexts()));
        // Closed-value sharing binds the forced nullList at the root, which the shared copy then reads.
        pins.put("null-list", new Pin(EDGE_NULL_LIST, true, -38, -40, listContexts()));
        for (var entry : pins.entrySet()) {
            var pin = entry.getValue();
            for (var level : LEVELS) {
                for (boolean maps : List.of(false, true)) {
                    for (boolean d2 : List.of(true, false)) {
                        String label = entry.getKey() + "/" + level + "/" + maps + "/d2=" + d2;
                        var on = compile(pin.source(), level, maps, true, d2);
                        var off = compile(pin.source(), level, maps, false, d2);
                        boolean shares = !maps && pin.shares();
                        assertEquals(shares, on.optimizationReport().appliedRules().contains(SharedBindings.RULE), label);
                        if (!shares) {
                            assertEquals(off.program(), on.program(), label);
                            assertEquals(off.optimizationReport(), on.optimizationReport(), label + ": report");
                            assertEquals(off.diagnostics(), on.diagnostics(), label + ": diagnostics");
                        }
                        assertSameInlined(off.program().term(), on.program().term(), label);
                        assertEquals(shares ? (d2 ? pin.bytes() : pin.bytesWithoutD2()) : 0, UplcFlatEncoder.encodeProgram(on.program()).length
                                - UplcFlatEncoder.encodeProgram(off.program()).length, label + ": size");
                        for (var context : pin.contexts()) {
                            String run = label + "/" + context.name();
                            var shared = CompilerTestVm.pv11().evaluateWithArgs(on.program(), List.of(context.data()));
                            var unshared = CompilerTestVm.pv11().evaluateWithArgs(off.program(), List.of(context.data()));
                            assertSameOutcome(unshared, shared, run);
                            assertEquals(unshared.budgetConsumed(), shared.budgetConsumed(), run);
                        }
                    }
                }
            }
        }
    }

    /**
     * Codex round 5: a run that fails at its handler's boundary check, before that handler's own bindings, evaluates
     * the shared bindings first and pays for them, like a run with no handler (ADR-076 I3). Runs that pass the check
     * cost the same.
     */
    @Test
    void aRunThatFailsAtItsBoundaryPaysForTheSharedBindings() {
        var policy = new PolicyId(new byte[28]);
        var credential = new Credential.PubKeyCredential(new PubKeyHash(new byte[28]));
        for (var level : LEVELS) {
            var on = compile(BOUNDARY, level, false, true, true);
            var off = compile(BOUNDARY, level, false, false, true);
            assertTrue(on.optimizationReport().appliedRules().contains(SharedBindings.RULE), level.toString());
            assertSameInlined(off.program().term(), on.program().term(), level.toString());
            for (var redeemer : List.of(PlutusData.integer(5), PlutusData.integer(1), PlutusData.bytes(new byte[]{1}))) {
                boolean failsAtBoundary = redeemer instanceof PlutusData.BytesData;
                for (var context : List.of(
                        ScriptContextTestBuilder.minting(policy).redeemer(redeemer).buildPlutusData(),
                        ScriptContextTestBuilder.rewarding(credential).redeemer(redeemer).buildPlutusData())) {
                    String run = level + "/" + redeemer;
                    var shared = CompilerTestVm.pv11().evaluateWithArgs(on.program(), List.of(context));
                    var unshared = CompilerTestVm.pv11().evaluateWithArgs(off.program(), List.of(context));
                    assertSameOutcome(unshared, shared, run);
                    if (failsAtBoundary) assertFalse(unshared.isSuccess(), run);
                    assertWholeSteps(failsAtBoundary ? BOUNDARY_SHARED_BINDING_STEPS : 0, unshared.budgetConsumed(),
                            shared.budgetConsumed(), run);
                }
            }
        }
    }

    /**
     * Codex round 4, P2: a chain of methods every handler reaches, each calling the next. The assembly names the
     * candidates and the optimizer's D1 pass, after D2, decides on its output without lowering anything again: the whole
     * chain is shared, and each handled run keeps its outcome and costs no more.
     */
    @Test
    void aLongChainOfSharedMethodsIsDecidedOnceAndShared() {
        int length = 300;
        var source = new StringBuilder(EDGE_HEAD).append("""
                    @Entrypoint(purpose = Purpose.MINT)
                    public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                        return h0(Builtins.unIData(redeemer)).compareTo(BigInteger.TEN) > 0;
                    }
                    @Entrypoint(purpose = Purpose.SPEND)
                    public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
                        return h0(Builtins.unIData(redeemer)).compareTo(BigInteger.TWO) > 0;
                    }
                """);
        for (int i = 0; i < length; i++) {
            source.append("    static BigInteger h").append(i).append("(BigInteger x) { return h").append(i + 1)
                    .append("(x).add(x); }\n");
        }
        source.append("    static BigInteger h").append(length).append("(BigInteger x) { return x.multiply(x); }\n}\n");
        for (var level : LEVELS) {
            var on = compile(source.toString(), level, false, true, false);
            var off = compile(source.toString(), level, false, false, false);
            assertTrue(on.optimizationReport().appliedRules().contains(SharedBindings.RULE), level.toString());
            assertTrue(UplcFlatEncoder.encodeProgram(on.program()).length < UplcFlatEncoder.encodeProgram(off.program()).length,
                    level.toString());
            for (var context : edgeContexts()) {
                String run = level + "/" + context.name();
                var shared = CompilerTestVm.pv11().evaluateWithArgs(on.program(), List.of(context.data()));
                var unshared = CompilerTestVm.pv11().evaluateWithArgs(off.program(), List.of(context.data()));
                assertSameOutcome(unshared, shared, run);
                assertTrue(shared.budgetConsumed().cpuSteps() <= unshared.budgetConsumed().cpuSteps(), run);
                assertTrue(shared.budgetConsumed().memoryUnits() <= unshared.budgetConsumed().memoryUnits(), run);
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

    /** Mint and spend contexts, without a parameter to apply, for the pinned fixtures. */
    private static List<Context> edgeContexts() {
        var contexts = new ArrayList<Context>();
        var policy = new PolicyId(new byte[28]);
        var reference = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
        for (long redeemer : List.of(0L, 3L, 20L)) {
            var value = PlutusData.integer(redeemer);
            contexts.add(new Context("mint", redeemer, ScriptContextTestBuilder.minting(policy).redeemer(value).buildPlutusData()));
            contexts.add(new Context("spend", redeemer,
                    ScriptContextTestBuilder.spending(reference, PlutusData.integer(1)).redeemer(value).buildPlutusData()));
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

    /** Mint and spend contexts whose redeemer is an empty list, and one that is not, for {@link #EDGE_NULL_LIST}. */
    private static List<Context> listContexts() {
        var contexts = new ArrayList<Context>();
        var policy = new PolicyId(new byte[28]);
        var reference = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
        for (var value : List.of(PlutusData.list(), PlutusData.list(PlutusData.integer(1)))) {
            long size = ((PlutusData.ListData) value).items().size();
            contexts.add(new Context("mint", size, ScriptContextTestBuilder.minting(policy).redeemer(value).buildPlutusData()));
            contexts.add(new Context("spend", size,
                    ScriptContextTestBuilder.spending(reference, PlutusData.integer(1)).redeemer(value).buildPlutusData()));
        }
        return contexts;
    }

    /** How many times {@code term} binds each method, {@code [(\Owner.m. body) value]}, by its name without the class. */
    private static Map<String, Integer> uplcBindings(Term term) {
        var bindings = new TreeMap<String, Integer>();
        var pending = new ArrayDeque<Term>();
        pending.push(term);
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case Term.Apply(var function, var argument) -> {
                    if (function instanceof Term.Lam(var name, var _) && name.contains(".")) bindings.merge(simpleName(name), 1, Integer::sum);
                    pending.push(function);
                    pending.push(argument);
                }
                case Term.Lam(var _, var body) -> pending.push(body);
                case Term.Force(var inner) -> pending.push(inner);
                case Term.Delay(var inner) -> pending.push(inner);
                case Term.Constr(var _, var fields) -> fields.forEach(pending::push);
                case Term.Case(var scrutinee, var branches) -> {
                    pending.push(scrutinee);
                    branches.forEach(pending::push);
                }
                default -> {
                }
            }
        }
        return bindings;
    }

    /**
     * {@code expected} and {@code actual} are the same program once every method binding is inlined, compared as FLAT,
     * which keeps no binder names: two handlers' copies of a method can name their binders differently.
     */
    private static void assertSameInlined(Term expected, Term actual, String label) {
        assertEquals(InlinedMethods.flat(expected), InlinedMethods.flat(actual), label);
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
