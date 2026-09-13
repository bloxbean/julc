package org.julclang.compiler;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.pir.ListIndexPromotionPass;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirSubstitution;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
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
import org.julclang.vm.ExBudget;
import org.julclang.vm.OptimizationCostProfile;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.julclang.compiler.O9ListIndexFixtures.EIGHT;
import static org.julclang.compiler.O9ListIndexFixtures.FIXTURES;
import static org.julclang.compiler.O9ListIndexFixtures.Path;
import static org.julclang.compiler.O9ListIndexFixtures.ints;
import static org.julclang.compiler.O9ListIndexFixtures.tens;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-043 (O9): the costed list-to-array promotion pass. Every fixture in
 * {@link O9ListIndexFixtures} is compiled at every level with source maps off and on and
 * compared with the golden bytes captured at the base commit
 * ({@code optimization/o9-pre-change-bytes.txt}): NONE, BASELINE and PV11_SAFE stay
 * byte-identical for every fixture, PV11_COSTED stays byte-identical for fixtures without a
 * promotable binding, and promoted programs are observationally equivalent on Java, Truffle and
 * Scalus for every input up to the documented out-of-range failure text. Direct-PIR cases pin
 * the eligibility and placement rules shape by shape, and the break-even is derived from the
 * pinned cost profile by measurement, never hard-coded.
 */
@Tag("pair-case-backends")
class O9ListIndexPromotionTest {

    private static final OptimizationCostProfile PROFILE = OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11;
    private static final PirType INT = new PirType.IntegerType();
    private static final PirType DATA = new PirType.DataType();
    private static final PirType LIST_INT = new PirType.ListType(INT);
    private static final List<String> PROVIDERS = List.of("Java", "Truffle", "Scalus");

    @Test
    void costedProfilePromotesRepeatedIndexingAndEveryOtherLevelKeepsItsBytes() throws IOException {
        var model = CostModel.get();
        for (int i = 0; i < FIXTURES.size(); i++) {
            var fixture = FIXTURES.get(i);
            for (var level : OptimizationLevel.values()) {
                for (boolean maps : List.of(false, true)) {
                    String label = fixture.name() + "/" + level + "/" + maps;
                    var compiled = compile(fixture.source(), fixture.method(), level, maps);
                    assertFalse(compiled.hasErrors(), label + " " + compiled.diagnostics());
                    var program = compiled.program();
                    var bytes = UplcFlatEncoder.encodeProgram(program);
                    assertArrayEquals(bytes, UplcFlatEncoder.encodeProgram(
                            compile(fixture.source(), fixture.method(), level, maps).program()), label);
                    boolean expectRule = level.pv11CostedRulesEnabled() && fixture.promotes();
                    assertEquals(expectRule, compiled.optimizationReport().appliedRules()
                            .contains(ListIndexPromotionPass.RULE), label);
                    assertEquals(expectRule ? fixture.arrays() : 0, countArrayBindings(compiled.pirTerm()), label);
                    if (expectRule) {
                        int sitesBefore = countRecursiveGets(compile(fixture.source(), fixture.method(), OptimizationLevel.PV11_SAFE, maps).pirTerm());
                        assertEquals(fixture.sitesLeft(), countRecursiveGets(compiled.pirTerm()), label);
                        assertEquals(sitesBefore - fixture.sitesLeft(), countIndexArray(compiled.pirTerm()) - manualIndexArraySites(fixture), label);
                    }
                    assertFalse(containsBuiltin(compiled.pirTerm(), DefaultFun.MultiIndexArray), label);
                    var old = golden(i, level, maps);
                    if (!expectRule) {
                        assertArrayEquals(UplcFlatEncoder.encodeProgram(old), bytes, label);
                    } else {
                        // Every promoted site drops a recursive binding (a fixpoint combinator and a
                        // two-parameter lambda) for one builtin application, so the program shrinks.
                        assertTrue(bytes.length < UplcFlatEncoder.encodeProgram(old).length, label);
                        assertNotEquals(JulcScriptAdapter.scriptHash(old), JulcScriptAdapter.scriptHash(program), label);
                    }
                    for (var input : fixture.inputs()) {
                        EvalResult javaResult = null;
                        for (String provider : PROVIDERS) {
                            String inputLabel = label + "/" + provider + "/" + input;
                            var before = evaluate(old, input.args(), provider);
                            var after = evaluate(program, input.args(), provider);
                            assertEquals(input.success(), before.isSuccess(), inputLabel + " " + before);
                            if (expectRule && input.path() == Path.DIVERGES_AT_CONVERSION) {
                                // The documented typing-trust exposure: a trusted list parameter
                                // holding a non-list fails at the conversion on every path below
                                // the binding, including one that never indexes.
                                var failure = assertInstanceOf(EvalResult.Failure.class, after, inputLabel);
                                if (!provider.equals("Scalus")) assertTrue(failure.error().startsWith("ListToArray:"), inputLabel + " " + failure.error());
                                if (provider.equals("Java")) javaResult = after;
                                if (provider.equals("Truffle")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), inputLabel);
                                continue;
                            }
                            assertEquals(input.success(), after.isSuccess(), inputLabel + " " + after);
                            assertEquals(before.getClass(), after.getClass(), inputLabel);
                            assertEquals(before.traces(), after.traces(), inputLabel);
                            if (before instanceof EvalResult.Success b) {
                                assertEquals(b.resultTerm(), ((EvalResult.Success) after).resultTerm(), inputLabel);
                            } else if (before instanceof EvalResult.Failure b) {
                                var a = (EvalResult.Failure) after;
                                if (expectRule && input.index() != null) {
                                    IndexFailureEquivalence.assertPromotedFailure(b, a, input.index(), input.size(), inputLabel);
                                } else {
                                    assertEquals(b.error(), a.error(), inputLabel);
                                }
                            }
                            if (provider.equals("Java")) javaResult = after;
                            // Truffle and Scalus charge exactly what the Java VM charges.
                            if (!provider.equals("Java")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), inputLabel);
                            if (level == OptimizationLevel.PV11_COSTED && !maps && provider.equals("Java")) {
                                System.out.println("LIST_TO_ARRAY_COST " + fixture.name() + " " + input + " "
                                        + before.budgetConsumed() + " -> " + after.budgetConsumed());
                            }
                            if (expectRule && provider.equals("Java")) {
                                assertBudgetPath(input, before.budgetConsumed(), after.budgetConsumed(), model, inputLabel);
                            }
                        }
                    }
                    if (level == OptimizationLevel.PV11_COSTED && !maps) {
                        System.out.println("LIST_TO_ARRAY_ARTIFACT " + fixture.name() + " "
                                + UplcFlatEncoder.encodeProgram(old).length + " -> " + bytes.length + " "
                                + JulcScriptAdapter.scriptHash(old) + " -> " + JulcScriptAdapter.scriptHash(program));
                    }
                }
            }
        }
    }

    /** The budget expectation of a fixture input, with the loss bounds measured from the profile. */
    private static void assertBudgetPath(O9ListIndexFixtures.Input input, ExBudget before, ExBudget after,
            CostModel model, String label) {
        switch (input.path()) {
            case SAVES -> {
                assertTrue(after.cpuSteps() < before.cpuSteps(), label + ": expected a saving, "
                        + before.cpuSteps() + " -> " + after.cpuSteps());
                assertTrue(after.memoryUnits() <= before.memoryUnits(), label + ": memory rose on a saving path, "
                        + before.memoryUnits() + " -> " + after.memoryUnits());
            }
            case PAYS -> {
                long cpuBound = 0;
                long memoryBound = 0;
                for (int length : input.arrayLengths()) {
                    cpuBound += model.listToArray(length) + model.binding();
                    memoryBound += model.listToArrayMemory(length) + model.bindingMemory();
                }
                assertTrue(after.cpuSteps() <= before.cpuSteps() + cpuBound, label + ": CPU loss beyond the array "
                        + "conversion bound " + cpuBound + ": " + before.cpuSteps() + " -> " + after.cpuSteps());
                assertTrue(after.memoryUnits() <= before.memoryUnits() + memoryBound, label + ": memory loss beyond "
                        + "the array conversion bound " + memoryBound + ": " + before.memoryUnits() + " -> " + after.memoryUnits());
            }
            case UNTOUCHED -> assertEquals(before, after, label + ": path without the array binding changed budget");
            case FAILS, DIVERGES_AT_CONVERSION -> { }
        }
    }

    /** {@code IndexArray} sites the source itself writes (the manual control), unchanged by the pass. */
    private static int manualIndexArraySites(O9ListIndexFixtures.Fixture fixture) {
        return countIndexArray(compile(fixture.source(), fixture.method(), OptimizationLevel.BASELINE, false).pirTerm());
    }

    @Test
    void costedOutputIsByteIdenticalToTheManualArraySource() {
        var two = FIXTURES.get(0);
        var manual = FIXTURES.get(1);
        assertEquals("TWO", two.name());
        assertEquals("MANUAL", manual.name());
        for (boolean maps : List.of(false, true)) {
            var automatic = compile(two.source(), two.method(), OptimizationLevel.PV11_COSTED, maps);
            var handWritten = compile(manual.source(), manual.method(), OptimizationLevel.PV11_COSTED, maps);
            assertArrayEquals(UplcFlatEncoder.encodeProgram(handWritten.program()),
                    UplcFlatEncoder.encodeProgram(automatic.program()), "maps=" + maps);
            assertTrue(automatic.optimizationReport().appliedRules().contains(ListIndexPromotionPass.RULE));
            assertFalse(handWritten.optimizationReport().appliedRules().contains(ListIndexPromotionPass.RULE));
            // The manual form is the same at the safe profile: arrays are a source-level feature there.
            assertArrayEquals(UplcFlatEncoder.encodeProgram(handWritten.program()), UplcFlatEncoder.encodeProgram(
                    compile(manual.source(), manual.method(), OptimizationLevel.PV11_SAFE, maps).program()), "maps=" + maps);
        }
    }

    /**
     * Structural pins: every array binding converts a variable, every rewritten site is a
     * two-argument {@code IndexArray} on such a binding, and the recognised {@code get} shape is
     * the one the registry emits (a change to the lowering that bypasses
     * {@link PirHelpers#recursiveListGet} fails here instead of silently disabling the rule).
     */
    @Test
    void arrayBindingsConvertVariablesAndRewrittenSitesAreIndexArrayOnly() {
        var single = FIXTURES.stream().filter(f -> f.name().equals("SINGLE")).findFirst().orElseThrow();
        var baseline = compile(single.source(), single.method(), OptimizationLevel.BASELINE, false);
        assertEquals(1, countRecursiveGets(baseline.pirTerm()));
        int[] recognised = {0};
        walk(baseline.pirTerm(), t -> { if (ListIndexPromotionPass.indexedList(t) != null) recognised[0]++; });
        assertEquals(1, recognised[0], "the registry no longer emits the shape the pass recognises");

        for (var fixture : FIXTURES) {
            var compiled = compile(fixture.source(), fixture.method(), OptimizationLevel.PV11_COSTED, false);
            var arrays = new java.util.HashSet<String>();
            walk(compiled.pirTerm(), term -> {
                if (term instanceof PirTerm.Let let && let.name().startsWith("#array-")) {
                    arrays.add(let.name());
                    var value = assertInstanceOf(PirTerm.App.class, let.value(), fixture.name());
                    assertEquals(new PirTerm.Builtin(DefaultFun.ListToArray), value.function(), fixture.name());
                    assertInstanceOf(PirTerm.Var.class, value.argument(), fixture.name() + ": array of a non-variable");
                }
            });
            int[] sites = {0};
            walk(compiled.pirTerm(), term -> {
                if (term instanceof PirTerm.App app && app.function() instanceof PirTerm.App inner
                        && inner.function() instanceof PirTerm.Builtin b && b.fun() == DefaultFun.IndexArray) {
                    sites[0]++;
                    var array = assertInstanceOf(PirTerm.Var.class, inner.argument(), fixture.name());
                    if (fixture.promotes()) assertTrue(arrays.contains(array.name()), fixture.name() + ": " + array.name());
                }
            });
            assertEquals(fixture.promotes() ? fixture.arrays() : 0, arrays.size(), fixture.name());
            if (fixture.promotes()) assertTrue(sites[0] >= fixture.arrays(), fixture.name());
        }
    }

    @Test
    void directPirPromotionRespectsScopesShadowingLoopsAndPlacement() {
        var xs = new PirTerm.Var("xs", LIST_INT);
        var ys = new PirTerm.Var("ys", LIST_INT);
        var pair = add(get(xs, 0), get(xs, 1));

        // Let-bound list with two sites: bound once, both sites rewritten; other levels are the identity.
        var letBound = new PirTerm.Let("xs", list(EIGHT), pair);
        var promoted = assertInstanceOf(PirTerm.Let.class, lower(letBound, OptimizationLevel.PV11_COSTED));
        assertEquals("xs", promoted.name());
        var array = assertInstanceOf(PirTerm.Let.class, promoted.body());
        assertEquals("#array-0", array.name());
        assertEquals(new PirTerm.App(new PirTerm.Builtin(DefaultFun.ListToArray), xs), array.value());
        assertEquals(0, countRecursiveGets(promoted));
        assertEquals(2, countIndexArray(promoted));
        for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE)) {
            assertSame(letBound, lower(letBound, level), level.toString());
        }
        assertEquivalent(letBound, 1);
        assertEquivalent(new PirTerm.Let("xs", list(tens(1)), pair), 1);
        assertEquivalent(new PirTerm.Let("xs", list(ints()), pair), 1);

        // Parameter chain: the binding sinks through the curried parameters to the branch that indexes.
        var chained = new PirTerm.Lam("xs", LIST_INT, new PirTerm.Lam("flag", new PirType.BoolType(),
                new PirTerm.IfThenElse(new PirTerm.Var("flag", new PirType.BoolType()), pair, new PirTerm.Const(Constant.integer(-1)))));
        var loweredChain = assertInstanceOf(PirTerm.Lam.class, lower(chained, OptimizationLevel.PV11_COSTED));
        var flagLam = assertInstanceOf(PirTerm.Lam.class, loweredChain.body());
        var ite = assertInstanceOf(PirTerm.IfThenElse.class, flagLam.body());
        assertInstanceOf(PirTerm.Let.class, ite.thenBranch());
        assertEquals(new PirTerm.Const(Constant.integer(-1)), ite.elseBranch());
        for (var flag : List.of(true, false)) {
            assertEquivalent(new PirTerm.App(new PirTerm.App(chained, list(EIGHT)), new PirTerm.Const(Constant.bool(flag))), 1);
        }

        // Exclusive branches with one site each count statically: bound above the conditional.
        var exclusive = new PirTerm.Let("xs", list(EIGHT), new PirTerm.IfThenElse(
                new PirTerm.Const(Constant.bool(true)), get(xs, 0), get(xs, 1)));
        var loweredExclusive = assertInstanceOf(PirTerm.Let.class, lower(exclusive, OptimizationLevel.PV11_COSTED));
        var exclusiveArray = assertInstanceOf(PirTerm.Let.class, loweredExclusive.body());
        assertEquals("#array-0", exclusiveArray.name());
        assertInstanceOf(PirTerm.IfThenElse.class, exclusiveArray.body());
        assertEquivalent(exclusive, 1);

        // A single site inside a callback lambda is not a repeat: untouched.
        var callback = new PirTerm.Let("xs", list(EIGHT), new PirTerm.App(
                new PirTerm.Var("f", new PirType.FunType(new PirType.FunType(INT, INT), INT)),
                new PirTerm.Lam("y", INT, add(new PirTerm.Var("y", INT), get(xs, 0)))));
        assertSame(callback, lower(callback, OptimizationLevel.PV11_COSTED));

        // Two sites inside a callback lambda: the binding wraps the lambda, built once, not per call.
        var callbackPair = new PirTerm.Let("xs", list(EIGHT), new PirTerm.App(
                new PirTerm.Var("f", new PirType.FunType(new PirType.FunType(INT, INT), INT)),
                new PirTerm.Lam("y", INT, add(new PirTerm.Var("y", INT), pair))));
        var loweredCallback = assertInstanceOf(PirTerm.Let.class, lower(callbackPair, OptimizationLevel.PV11_COSTED));
        var application = assertInstanceOf(PirTerm.App.class, loweredCallback.body());
        var wrapped = assertInstanceOf(PirTerm.Let.class, application.argument());
        assertEquals("#array-0", wrapped.name());
        assertInstanceOf(PirTerm.Lam.class, wrapped.body());

        // One site under a recursive binding (a loop) is a repeat: bound once, above the recursion.
        var i = new PirTerm.Var("i", INT);
        var go = new PirTerm.Var("go", new PirType.FunType(INT, INT));
        var loopBody = new PirTerm.Lam("i", INT, new PirTerm.IfThenElse(
                app2(DefaultFun.EqualsInteger, i, new PirTerm.Const(Constant.integer(8))),
                new PirTerm.Const(Constant.integer(0)),
                add(get(xs, i), new PirTerm.App(go, app2(DefaultFun.AddInteger, i, new PirTerm.Const(Constant.integer(1)))))));
        var loop = new PirTerm.Let("xs", list(EIGHT), new PirTerm.LetRec(List.of(new PirTerm.Binding("go", loopBody)),
                new PirTerm.App(go, new PirTerm.Const(Constant.integer(0)))));
        var loweredLoop = assertInstanceOf(PirTerm.Let.class, lower(loop, OptimizationLevel.PV11_COSTED));
        var loopArray = assertInstanceOf(PirTerm.Let.class, loweredLoop.body());
        assertEquals("#array-0", loopArray.name());
        assertInstanceOf(PirTerm.LetRec.class, loopArray.body());
        assertEquals(0, countRecursiveGets(loweredLoop));
        assertEquivalent(loop, 1);

        // Rebinding: an inner let named xs is a different list; the outer pair is promoted, the
        // inner single site stays, and the inner site reads the inner list (7, not 0).
        var rebound = new PirTerm.Let("xs", list(EIGHT), new PirTerm.Let("ys", list(ints(7)),
                add(pair, new PirTerm.Let("xs", ys, get(xs, 0)))));
        var loweredRebound = lower(rebound, OptimizationLevel.PV11_COSTED);
        assertEquals(1, countRecursiveGets(loweredRebound));
        assertEquals(2, countIndexArray(loweredRebound));
        assertEquals(10 + 7, integerResult(rebound, 1));

        // Match field bound to a list: promoted inside the branch that binds it.
        var holder = PlutusData.constr(0, tens(4), PlutusData.integer(1));
        var items = new PirTerm.Var("items", LIST_INT);
        var matched = new PirTerm.Let("d", new PirTerm.Const(Constant.data(holder)), new PirTerm.DataMatch(
                new PirTerm.Var("d", DATA), List.of(new PirTerm.MatchBranch("Holder", List.of("items", "n"),
                        List.of(LIST_INT, INT), add(get(items, 0), get(items, 1))))));
        var loweredMatch = assertInstanceOf(PirTerm.Let.class, lower(matched, OptimizationLevel.PV11_COSTED));
        var match = assertInstanceOf(PirTerm.DataMatch.class, loweredMatch.body());
        assertInstanceOf(PirTerm.Let.class, match.branches().getFirst().body());
        assertEquals(10, integerResult(matched, 1));

        // The index of one site holds a site of another list: both lists are promoted.
        var crossed = new PirTerm.Let("xs", list(EIGHT), new PirTerm.Let("ys", list(ints(3, 5)),
                add(get(xs, get(ys, 0)), add(get(xs, get(ys, 1)), get(ys, 1)))));
        var loweredCrossed = lower(crossed, OptimizationLevel.PV11_COSTED);
        assertEquals(0, countRecursiveGets(loweredCrossed));
        assertEquals(2, countArrayBindings(loweredCrossed));
        assertEquals(30 + 50 + 5, integerResult(crossed, 2));

        // A trace before the sites stays first: the binding lands inside the trace body.
        var traced = new PirTerm.Let("xs", list(EIGHT),
                new PirTerm.Trace(new PirTerm.Const(Constant.string("first")), pair));
        var loweredTrace = assertInstanceOf(PirTerm.Let.class, lower(traced, OptimizationLevel.PV11_COSTED));
        var trace = assertInstanceOf(PirTerm.Trace.class, loweredTrace.body());
        assertInstanceOf(PirTerm.Let.class, trace.body());
        assertEquivalent(traced, 1);
        assertEquivalent(new PirTerm.Let("xs", list(ints()), new PirTerm.Trace(new PirTerm.Const(Constant.string("first")), pair)), 1);

        // An error in one branch: the binding sinks into the indexing branch, the error path is untouched.
        var guardedByError = new PirTerm.Let("xs", list(EIGHT), new PirTerm.IfThenElse(
                new PirTerm.Var("flag", new PirType.BoolType()), new PirTerm.Error(INT), pair));
        var loweredError = assertInstanceOf(PirTerm.Let.class, lower(guardedByError, OptimizationLevel.PV11_COSTED));
        var errorBranch = assertInstanceOf(PirTerm.IfThenElse.class, loweredError.body());
        assertInstanceOf(PirTerm.Error.class, errorBranch.thenBranch());
        assertInstanceOf(PirTerm.Let.class, errorBranch.elseBranch());
        for (var flag : List.of(true, false)) {
            assertEquivalent(new PirTerm.Let("flag", new PirTerm.Const(Constant.bool(flag)), guardedByError), 1);
        }

        // The loop lowering's post-loop self-alias (let xs = xs) is transparent: one conversion
        // serves the sites inside the loop and after it.
        var aliased = new PirTerm.Let("xs", list(EIGHT), add(
                new PirTerm.LetRec(List.of(new PirTerm.Binding("go", loopBody)), new PirTerm.App(go, new PirTerm.Const(Constant.integer(6)))),
                new PirTerm.Let("xs", xs, pair)));
        var loweredAlias = lower(aliased, OptimizationLevel.PV11_COSTED);
        assertEquals(1, countArrayBindings(loweredAlias));
        assertEquals(0, countRecursiveGets(loweredAlias));
        assertEquals(60 + 70 + 10, integerResult(aliased, 1));

        // A Let whose value is a bare Data term (the lowering of a cast to JulcList) is not a
        // list by construction and is never promoted, so a non-list on a non-indexing path
        // keeps succeeding; the same sites behind a decode promote.
        var d = new PirTerm.Var("d", DATA);
        var castChain = new PirTerm.Let("d", new PirTerm.Const(Constant.data(PlutusData.integer(5))),
                new PirTerm.Let("xs", d, new PirTerm.IfThenElse(new PirTerm.Var("flag", new PirType.BoolType()),
                        pair, new PirTerm.Const(Constant.integer(-1)))));
        assertSame(castChain, lower(castChain, OptimizationLevel.PV11_COSTED));
        assertEquivalent(new PirTerm.Let("flag", new PirTerm.Const(Constant.bool(false)), castChain), 0);
        var decodedChain = new PirTerm.Let("d", new PirTerm.Const(Constant.data(EIGHT)),
                new PirTerm.Let("xs", new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), d),
                        new PirTerm.IfThenElse(new PirTerm.Var("flag", new PirType.BoolType()), pair, new PirTerm.Const(Constant.integer(-1)))));
        assertEquals(1, countArrayBindings(lower(decodedChain, OptimizationLevel.PV11_COSTED)));
    }

    /**
     * Whether a variable holds a list is decided by how it was bound, never by the type its
     * uses carry: a list-typed alias inherits the proof of the variable it copies. The loop
     * lowering emits exactly such an alias ({@code let xs = xs}, with the declared list type)
     * after every loop, so without this rule an unproven cast local would become promotable the
     * moment a loop preceded its sites, and the never-indexing path would fail at the
     * conversion. Aliases of proven lists (a parameter, a decode, a list-match tail) promote;
     * copies of binders that are not lists by construction (a list-match head, a pattern
     * variable, a non-list match field, a recursive binding) do not, whatever type they carry.
     */
    @Test
    void listTypedAliasesInheritTheProofOfTheirBinding() {
        var xs = new PirTerm.Var("xs", LIST_INT);
        var ys = new PirTerm.Var("ys", LIST_INT);
        var zs = new PirTerm.Var("zs", LIST_INT);
        var d = new PirTerm.Var("d", DATA);
        var flag = new PirTerm.Var("flag", new PirType.BoolType());
        var pairOnXs = add(get(xs, 0), get(xs, 1));
        var pairOnYs = add(get(ys, 0), get(ys, 1));
        var five = new PirTerm.Const(Constant.data(PlutusData.integer(5)));
        UnaryOperator<PirTerm> behindFlag = body -> new PirTerm.IfThenElse(flag, body, new PirTerm.Const(Constant.integer(-1)));
        UnaryOperator<PirTerm> flagOff = body -> new PirTerm.Let("flag", new PirTerm.Const(Constant.bool(false)), body);

        // The cast local self-aliased as the loop lowering does, and copied into a list-typed
        // alias: neither alias is proven, nothing changes, the non-indexing path keeps succeeding.
        var castSelfAlias = new PirTerm.Let("d", five, new PirTerm.Let("xs", d,
                new PirTerm.Let("xs", xs, behindFlag.apply(pairOnXs))));
        var castAlias = new PirTerm.Let("d", five, new PirTerm.Let("xs", d,
                new PirTerm.Let("ys", xs, behindFlag.apply(pairOnYs))));
        var castAliasChain = new PirTerm.Let("d", five, new PirTerm.Let("xs", d,
                new PirTerm.Let("ys", xs, new PirTerm.Let("zs", ys, behindFlag.apply(add(get(zs, 0), get(zs, 1)))))));
        for (var unproven : List.of(castSelfAlias, castAlias, castAliasChain)) {
            assertSame(unproven, lower(unproven, OptimizationLevel.PV11_COSTED));
            assertEquals(-1, integerResult(flagOff.apply(unproven), 0));
        }

        // The same aliases of a proven list promote, with the array bound below the alias.
        var aliasOfParam = new PirTerm.Lam("xs", LIST_INT, new PirTerm.Let("ys", xs, pairOnYs));
        var loweredParamAlias = assertInstanceOf(PirTerm.Lam.class, lower(aliasOfParam, OptimizationLevel.PV11_COSTED));
        var aliasLet = assertInstanceOf(PirTerm.Let.class, loweredParamAlias.body());
        assertEquals("ys", aliasLet.name());
        var arrayLet = assertInstanceOf(PirTerm.Let.class, aliasLet.body());
        assertEquals(new PirTerm.App(new PirTerm.Builtin(DefaultFun.ListToArray), ys), arrayLet.value());
        assertEquals(0, countRecursiveGets(loweredParamAlias));
        assertEquals(10, integerResult(new PirTerm.App(aliasOfParam, list(EIGHT)), 1));

        var selfAliasOfParam = new PirTerm.Lam("xs", LIST_INT, new PirTerm.Let("xs", xs, pairOnXs));
        assertEquals(1, countArrayBindings(lower(selfAliasOfParam, OptimizationLevel.PV11_COSTED)));
        assertEquals(10, integerResult(new PirTerm.App(selfAliasOfParam, list(EIGHT)), 1));

        var decodedChain = new PirTerm.Let("d", new PirTerm.Const(Constant.data(EIGHT)),
                new PirTerm.Let("xs", new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), d),
                        new PirTerm.Let("ys", xs, new PirTerm.Let("zs", ys, add(get(zs, 0), get(zs, 1))))));
        assertEquals(1, countArrayBindings(lower(decodedChain, OptimizationLevel.PV11_COSTED)));
        assertEquals(10, integerResult(decodedChain, 1));

        var tailAlias = new PirTerm.ListMatch(list(ints(5, 7, 9)), "head", "tail", new PirTerm.Const(Constant.integer(0)),
                new PirTerm.Let("ys", new PirTerm.Var("tail", LIST_INT), pairOnYs));
        assertEquals(1, countArrayBindings(lower(tailAlias, OptimizationLevel.PV11_COSTED)));
        assertEquals(7 + 9, integerResult(tailAlias, 1));

        // Binders that are not lists by construction shadow an outer proven name: a copy of
        // them is unproven even though the copy's uses say list.
        var holderOfSeven = new PirTerm.Const(Constant.data(PlutusData.constr(0, PlutusData.integer(7))));
        var copied = new PirTerm.Let("ys", xs, behindFlag.apply(pairOnYs));
        record Shadow(String name, PirTerm body) {}
        var shadows = List.of(
                new Shadow("list-match-head", new PirTerm.ListMatch(list(ints(7, 9)), "xs", "tail",
                        new PirTerm.Const(Constant.integer(0)), copied)),
                new Shadow("match-pattern-variable", new PirTerm.DataMatch(holderOfSeven, List.of(
                        new PirTerm.MatchBranch("A", List.of(), List.of(), copied, "xs")))),
                new Shadow("non-list-match-field", new PirTerm.DataMatch(holderOfSeven, List.of(
                        new PirTerm.MatchBranch("A", List.of("xs"), List.of(INT), copied)))),
                new Shadow("pair-match-component", new PirTerm.PairMatch(
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), holderOfSeven),
                        new PirType.PairType(INT, new PirType.ListType(DATA)), "tag", "xs", copied)),
                new Shadow("recursive-binding", new PirTerm.LetRec(List.of(new PirTerm.Binding("xs",
                        new PirTerm.Lam("i", INT, new PirTerm.Var("i", INT)))), copied)));
        for (var shadow : shadows) {
            var term = new PirTerm.Lam("xs", LIST_INT, shadow.body());
            assertSame(term, lower(term, OptimizationLevel.PV11_COSTED), shadow.name());
        }

        // A callback lambda's list-typed parameter is not a list by construction: the list
        // operations apply callbacks to their raw Data elements. Sites on it are never promoted,
        // while the same lambda as a method (the root, or a recursive binding) promotes.
        var callbackWithSites = new PirTerm.Lam("xs", LIST_INT, behindFlag.apply(pairOnXs));
        var asCallback = new PirTerm.App(new PirTerm.Var("f", new PirType.FunType(new PirType.FunType(LIST_INT, INT), INT)), callbackWithSites);
        assertSame(asCallback, lower(asCallback, OptimizationLevel.PV11_COSTED));
        var asHelper = new PirTerm.LetRec(List.of(new PirTerm.Binding("helper", callbackWithSites)),
                new PirTerm.App(new PirTerm.Var("helper", new PirType.FunType(LIST_INT, INT)), list(EIGHT)));
        assertEquals(1, countArrayBindings(lower(asHelper, OptimizationLevel.PV11_COSTED)));
        assertEquals(1, countArrayBindings(lower(callbackWithSites, OptimizationLevel.PV11_COSTED)));
        assertEquals(10, integerResult(new PirTerm.Let("flag", new PirTerm.Const(Constant.bool(true)), asHelper), 1));

        // The CAST_LOOP_ALIAS fixture exercises the generated shape: the loop lowering really
        // does rebind the cast local as a list-typed self-alias after the loop, so the fixture's
        // byte-identity at the costed level rests on this rule and not on the alias being absent.
        var fixture = FIXTURES.stream().filter(f -> f.name().equals("CAST_LOOP_ALIAS")).findFirst().orElseThrow();
        var pir = compile(fixture.source(), fixture.method(), OptimizationLevel.PV11_SAFE, false).pirTerm();
        int[] selfAliases = {0};
        walk(pir, t -> {
            if (t instanceof PirTerm.Let let && let.name().equals("xs")
                    && let.value() instanceof PirTerm.Var v && v.name().equals("xs") && v.type() instanceof PirType.ListType) {
                selfAliases[0]++;
            }
        });
        assertEquals(1, selfAliases[0], "the loop lowering's post-loop self-alias of the cast local");
        assertEquals(2, countRecursiveGets(pir));
    }

    /**
     * Every binder kind that can rebind the list name inside a promoted scope: the inner single
     * site belongs to the inner binding and stays recursive, the outer pair promotes, and the
     * result proves which list each site read. With a single outer site nothing is eligible.
     * Removing any rebinding guard in {@code collectSites}/{@code replaceSites} changes a
     * result or promotes a once-indexed list.
     */
    @Test
    void everyRebindingBinderKindIsOpaqueToTheOuterBinding() {
        var xs = new PirTerm.Var("xs", LIST_INT);
        var pair = add(get(xs, 0), get(xs, 1));
        var single = get(xs, 0);
        var seven = list(ints(7));
        var holderOfSeven = new PirTerm.Const(Constant.data(PlutusData.constr(0, ints(7))));
        record Rebinding(String name, PirTerm inner, Long expected) {}
        var shapes = List.of(
                new Rebinding("lambda", new PirTerm.App(new PirTerm.Lam("xs", LIST_INT, get(xs, 0)), seven), 17L),
                new Rebinding("match-field", new PirTerm.DataMatch(holderOfSeven, List.of(
                        new PirTerm.MatchBranch("A", List.of("xs"), List.of(LIST_INT), get(xs, 0)))), 17L),
                new Rebinding("match-pattern-variable", new PirTerm.DataMatch(holderOfSeven, List.of(
                        new PirTerm.MatchBranch("A", List.of(), List.of(), get(xs, 0), "xs"))), null),
                new Rebinding("list-match-tail", new PirTerm.ListMatch(list(ints(7, 9)), "head", "xs",
                        new PirTerm.Const(Constant.integer(0)), get(xs, 0)), 19L),
                // The pair's second field is the constructor's field list itself: [7].
                new Rebinding("pair-match-second", new PirTerm.PairMatch(
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData),
                                new PirTerm.Const(Constant.data(PlutusData.constr(0, PlutusData.integer(7))))),
                        new PirType.PairType(INT, new PirType.ListType(DATA)), "tag", "xs", get(xs, 0)), 17L),
                new Rebinding("recursive-binding", new PirTerm.LetRec(List.of(new PirTerm.Binding("xs",
                        new PirTerm.Lam("i", INT, new PirTerm.Var("i", INT)))), get(xs, 0)), null));
        for (var shape : shapes) {
            var outerPair = new PirTerm.Let("xs", list(EIGHT), add(pair, shape.inner()));
            var lowered = lower(outerPair, OptimizationLevel.PV11_COSTED);
            assertEquals(1, countArrayBindings(lowered), shape.name());
            assertEquals(1, countRecursiveGets(lowered), shape.name() + ": inner site must stay recursive");
            assertEquals(2, countIndexArray(lowered), shape.name());
            if (shape.expected() != null) {
                assertEquals(shape.expected(), integerResult(outerPair, 1), shape.name());
            } else {
                // The inner name is not a list at all: both levels fail at the inner site with
                // the same text, after the promoted pair succeeded.
                assertEquivalent(outerPair, 1);
                assertInstanceOf(EvalResult.Failure.class,
                        evaluate(compileDirect(outerPair, OptimizationLevel.PV11_COSTED), List.of(), "Java"), shape.name());
            }
            var outerSingle = new PirTerm.Let("xs", list(EIGHT), add(single, shape.inner()));
            assertSame(outerSingle, lower(outerSingle, OptimizationLevel.PV11_COSTED), shape.name() + ": one outer site is not a repeat");
        }
    }

    /**
     * The failure contract end to end: a validator compiled through the strict typed boundary
     * with an index supplied by the redeemer. The boundary decodes the integer with a bare
     * {@code unIData} and no range check, so an out-of-range index reaches the promoted sites
     * and the validator itself reports the array text at the costed level, on all three VMs.
     */
    @Test
    void validatorObservesTheIndexFailureContractThroughTheStrictBoundary() {
        var validator = O9ListIndexFixtures.IMPORTS + """
                import org.julclang.ledger.*;
                @SpendingValidator class IndexedByRedeemer {
                    @Param JulcList<BigInteger> allowed;
                    @Entrypoint static boolean validate(PlutusData datum, BigInteger index, ScriptContext ctx) {
                        return allowed.get(index).add(allowed.get(0)).equals(BigInteger.valueOf(70));
                    }
                }
                """;
        var safe = compileValidator(validator, OptimizationLevel.PV11_SAFE);
        var costed = compileValidator(validator, OptimizationLevel.PV11_COSTED);
        assertTrue(costed.optimizationReport().appliedRules().contains(ListIndexPromotionPass.RULE));
        for (long index : List.of(7L, 8L, 99L, -1L)) {
            // V3 script context: [txInfo, redeemer, Spending(txOutRef, NoDatum)]; the strict
            // boundary only decodes the redeemer integer and the spending purpose.
            var spending = PlutusData.constr(1, PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0)),
                    PlutusData.constr(0, PlutusData.integer(0)));
            var context = PlutusData.constr(0, PlutusData.integer(0), PlutusData.integer(index), spending);
            for (String provider : PROVIDERS) {
                var before = evaluate(applyParam(safe.program(), EIGHT), List.of(context), provider);
                var after = evaluate(applyParam(costed.program(), EIGHT), List.of(context), provider);
                String label = provider + "/index=" + index;
                if (index == 7) {
                    assertTrue(before.isSuccess() && after.isSuccess(), label);
                } else {
                    var b = assertInstanceOf(EvalResult.Failure.class, before, label);
                    var a = assertInstanceOf(EvalResult.Failure.class, after, label);
                    IndexFailureEquivalence.assertPromotedFailure(b, a, index, 8, label);
                }
            }
        }
    }

    private static CompileResult compileValidator(String source, OptimizationLevel level) {
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setOptimizationLevel(level).setOptimizationCostProfile(PROFILE)).compileWithDetails(source);
        assertFalse(compiled.hasErrors(), level + " " + compiled.diagnostics());
        return compiled;
    }

    /** Apply the script's {@code @Param} lambda to its value, as deployment does. */
    private static Program applyParam(Program program, PlutusData value) {
        return new Program(program.major(), program.minor(), program.patch(),
                Term.apply(program.term(), Term.const_(Constant.data(value))));
    }

    /**
     * The break-even is derived from the pinned profile. Standalone measurements give the
     * constants: a recursive {@code get} site (with its element decode) is affine in the index,
     * {@code ListToArray} affine in the length, an {@code IndexArray} site (with the same decode)
     * flat. The before/after delta of whole two-, three- and four-site programs is then measured
     * on a grid and must equal {@code binding + listToArray(n) + k·indexSite − Σ get(i)} exactly;
     * the crossover table in ADR-043 is computed from those constants, never typed in.
     */
    @Test
    void breakEvenDerivesFromThePinnedCostProfile() {
        var model = CostModel.get();
        System.out.println("O9_MODEL get=" + model.getFixed() + "+" + model.getStep() + "*i listToArray="
                + model.listToArrayFixed() + "+" + model.listToArrayStep() + "*n indexArray=" + model.indexSite()
                + " binding=" + model.binding() + " perSite=" + (model.indexSite() - model.getFixed())
                + " memory: listToArray=" + model.listToArrayMemoryFixed() + "+" + model.listToArrayMemoryStep()
                + "*n binding=" + model.bindingMemory());
        assertTrue(model.getStep() > 0 && model.listToArrayStep() > 0 && model.indexSite() > 0);
        // One traversal step costs far more than converting one element: promotion pays for
        // almost any list when a site's index is at least one.
        assertTrue(model.getStep() > 10 * model.listToArrayStep());

        for (int n : List.of(2, 8, 32, 64, 128)) {
            for (var indices : List.of(List.of(0, 0), List.of(0, 1), List.of(1, 2), List.of(3, 7),
                    List.of(0, 1, 2), List.of(2, 5, 7), List.of(0, 1, 2, 3))) {
                if (indices.stream().anyMatch(index -> index >= n)) continue;
                long measured = CostModel.measuredDelta(n, indices);
                long predicted = model.predictedDelta(n, indices);
                String label = "n=" + n + " indices=" + indices;
                System.out.println("O9_DELTA " + label + " measured=" + measured + " predicted=" + predicted);
                assertEquals(predicted, measured, label);
            }
        }
        for (var indices : List.of(List.of(0, 0), List.of(0, 1), List.of(1, 2), List.of(0, 7), List.of(3, 7),
                List.of(0, 1, 2), List.of(1, 2, 3), List.of(0, 1, 2, 3))) {
            System.out.println("O9_BREAKEVEN indices=" + indices + " gainsUpToLength=" + model.breakEvenLength(indices));
        }
        // Two sites at indices 0 and 1 gain for lists of a few dozen elements and lose beyond.
        long twoLowSites = model.breakEvenLength(List.of(0, 1));
        assertTrue(twoLowSites >= 20 && twoLowSites <= 80, Long.toString(twoLowSites));
        // Two sites at index 0 lose unless the list is tiny.
        assertTrue(model.breakEvenLength(List.of(0, 0)) < 20);
    }

    /**
     * Validator shapes compile at every level with no free variables: a {@code @Param} list is a
     * lambda outside the library lets, and a record field bound in the entry point is a strict
     * let. Promotion lands inside the parameter chain and below the decode lets.
     */
    @Test
    void validatorParametersAndDatumFieldsPromoteInsideTheirScopes() {
        var validator = O9ListIndexFixtures.IMPORTS + """
                import org.julclang.ledger.*;
                @SpendingValidator class ParamValidator {
                    @Param JulcList<BigInteger> allowed;
                    record Datum(JulcList<BigInteger> items, BigInteger n) {}
                    static boolean firstTwoAllowed(BigInteger a, BigInteger b) {
                        return allowed.get(0).equals(a) && allowed.get(1).equals(b);
                    }
                    @Entrypoint static boolean validate(Datum datum, PlutusData redeemer, ScriptContext ctx) {
                        JulcList<BigInteger> items = datum.items();
                        return firstTwoAllowed(items.get(0), items.get(1));
                    }
                }
                """;
        for (var level : OptimizationLevel.values()) {
            var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                    .setOptimizationLevel(level).setOptimizationCostProfile(PROFILE))
                    .compileWithDetails(validator);
            assertFalse(compiled.hasErrors(), level + " " + compiled.diagnostics());
            assertEquals(Set.of(), PirSubstitution.collectFreeVarNames(compiled.pirTerm()), level.toString());
            boolean costed = level.pv11CostedRulesEnabled();
            assertEquals(costed, compiled.optimizationReport().appliedRules().contains(ListIndexPromotionPass.RULE), level.toString());
            assertEquals(costed ? 2 : 0, countArrayBindings(compiled.pirTerm()), level.toString());
            assertEquals(costed ? 0 : 4, countRecursiveGets(compiled.pirTerm()), level.toString());
        }

        var source = O9ListIndexFixtures.IMPORTS + """
                class ParamShapes {
                    @Param JulcList<BigInteger> allowed;
                    static BigInteger firstTwo() { return allowed.get(0).add(allowed.get(1)); }
                }
                """;
        var baseline = compile(source, "firstTwo", OptimizationLevel.BASELINE, false);
        var costed = compile(source, "firstTwo", OptimizationLevel.PV11_COSTED, false);
        assertFalse(baseline.hasErrors(), baseline.diagnostics().toString());
        assertFalse(costed.hasErrors(), costed.diagnostics().toString());
        assertTrue(costed.optimizationReport().appliedRules().contains(ListIndexPromotionPass.RULE));
        assertEquals(1, countArrayBindings(costed.pirTerm()));
        for (var allowed : List.of(EIGHT, tens(1), ints())) {
            for (String provider : PROVIDERS) {
                var before = evaluate(baseline.program(), List.of(allowed), provider);
                var after = evaluate(costed.program(), List.of(allowed), provider);
                assertEquals(before.getClass(), after.getClass(), provider + "/" + allowed);
                if (before instanceof EvalResult.Success b) assertEquals(b.resultTerm(), ((EvalResult.Success) after).resultTerm(), provider);
                if (before instanceof EvalResult.Failure b) IndexFailureEquivalence.assertFailureTextEquivalent(b, (EvalResult.Failure) after, provider);
            }
        }
    }

    @Test
    void theRuleIsInertWithoutRepeatsAndBelowTheCostedLevel() {
        for (var fixture : FIXTURES) {
            if (fixture.promotes()) continue;
            for (var level : OptimizationLevel.values()) {
                var compiled = compile(fixture.source(), fixture.method(), level, false);
                assertFalse(compiled.optimizationReport().appliedRules().contains(ListIndexPromotionPass.RULE),
                        fixture.name() + "/" + level);
            }
        }
        for (var fixture : FIXTURES) {
            if (!fixture.promotes()) continue;
            for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE)) {
                var compiled = compile(fixture.source(), fixture.method(), level, false);
                assertFalse(compiled.optimizationReport().appliedRules().contains(ListIndexPromotionPass.RULE),
                        fixture.name() + "/" + level);
                assertEquals(0, countArrayBindings(compiled.pirTerm()), fixture.name() + "/" + level);
            }
        }
    }

    // --- cost model, measured once from the pinned profile on the Java VM ---

    /**
     * Machine costs under the pinned profile, each measured as a program difference so the
     * shared list expression cancels: {@code get} at index {@code i} costs
     * {@code getFixed + getStep * i}, {@code ListToArray} of {@code n} elements costs
     * {@code listToArrayFixed + listToArrayStep * n}, one rewritten site costs {@code indexSite},
     * and the array binding one lambda, one application and one variable lookup.
     */
    record CostModel(long getFixed, long getStep, long listToArrayFixed, long listToArrayStep,
                     long indexSite, long binding, long listToArrayMemoryFixed, long listToArrayMemoryStep,
                     long bindingMemory) {

        private static CostModel instance;

        static synchronized CostModel get() {
            if (instance == null) instance = measure();
            return instance;
        }

        long listToArray(int n) { return listToArrayFixed + listToArrayStep * n; }

        long listToArrayMemory(int n) { return listToArrayMemoryFixed + listToArrayMemoryStep * n; }

        long recursiveGet(long index) { return getFixed + getStep * index; }

        /** After minus before for these sites on a list of {@code n} elements. */
        long predictedDelta(int n, List<Integer> indices) {
            long promoted = binding + listToArray(n) + indexSite * indices.size();
            long recursive = indices.stream().mapToLong(this::recursiveGet).sum();
            return promoted - recursive;
        }

        /** The largest list length for which promoting these sites still saves CPU (−1 if none). */
        long breakEvenLength(List<Integer> indices) {
            long n = -1;
            while (predictedDelta((int) n + 1, indices) < 0) n++;
            return n;
        }

        /** Costed minus safe CPU of {@code let xs = list(n) in get(xs, i1) + get(xs, i2) + …}. */
        static long measuredDelta(int n, List<Integer> indices) {
            var xs = new PirTerm.Var("xs", LIST_INT);
            PirTerm body = null;
            for (int index : indices) body = body == null ? getAt(xs, index) : add(body, getAt(xs, index));
            var program = new PirTerm.Let("xs", list(tens(n)), body);
            return cpu(compileDirect(program, OptimizationLevel.PV11_COSTED), "Java")
                    - cpu(compileDirect(program, OptimizationLevel.PV11_SAFE), "Java");
        }

        /**
         * Every constant is a program difference at the safe profile (the "before" of every
         * promotion) so the shared list expression cancels, and both site forms carry the same
         * element decode, exactly as they appear in a promoted program.
         */
        private static CostModel measure() {
            long[] listCost = new long[129];
            long[] listMemory = new long[129];
            for (int n : List.of(0, 1, 8, 64, 128)) {
                var budget = budget(direct(list(tens(n))));
                listCost[n] = budget.cpuSteps();
                listMemory[n] = budget.memoryUnits();
            }
            long get0 = cpu(direct(getAt(list(tens(8)), 0)), "Java") - listCost[8];
            long get1 = cpu(direct(getAt(list(tens(8)), 1)), "Java") - listCost[8];
            long get2 = cpu(direct(getAt(list(tens(8)), 2)), "Java") - listCost[8];
            long get7 = cpu(direct(getAt(list(tens(8)), 7)), "Java") - listCost[8];
            long step = get1 - get0;
            assertEquals(step, get2 - get1, "recursive get is affine in the index");
            assertEquals(get0 + 7 * step, get7, "recursive get is affine in the index");
            assertEquals(get1, cpu(direct(getAt(list(tens(64)), 1)), "Java") - listCost[64], "get cost does not depend on length");
            long[] l2a = new long[129];
            long[] l2aMemory = new long[129];
            for (int n : List.of(0, 1, 8, 64, 128)) {
                var budget = budget(direct(O9ListIndexPromotionTest.listToArray(list(tens(n)))));
                l2a[n] = budget.cpuSteps() - listCost[n];
                l2aMemory[n] = budget.memoryUnits() - listMemory[n];
            }
            long l2aStep = l2a[1] - l2a[0];
            long l2aMemoryStep = l2aMemory[1] - l2aMemory[0];
            for (int n : List.of(8, 64, 128)) {
                assertEquals(l2a[0] + n * l2aStep, l2a[n], "ListToArray CPU is affine in the length");
                assertEquals(l2aMemory[0] + n * l2aMemoryStep, l2aMemory[n], "ListToArray memory is affine in the length");
            }
            // A rewritten site is unIData [[indexArray a] i] with a bound array; measured against
            // the bound array alone so the array lookup is a variable in both.
            var arrayVar = new PirTerm.Var("a", new PirType.ArrayType(INT));
            long bound = cpu(direct(new PirTerm.Let("a", O9ListIndexPromotionTest.listToArray(list(tens(8))), arrayVar)), "Java");
            long site0 = cpu(direct(new PirTerm.Let("a", O9ListIndexPromotionTest.listToArray(list(tens(8))),
                    unI(app2(DefaultFun.IndexArray, arrayVar, new PirTerm.Const(Constant.integer(0)))))), "Java") - bound;
            long site7 = cpu(direct(new PirTerm.Let("a", O9ListIndexPromotionTest.listToArray(list(tens(8))),
                    unI(app2(DefaultFun.IndexArray, arrayVar, new PirTerm.Const(Constant.integer(7)))))), "Java") - bound;
            assertEquals(site0, site7, "IndexArray cost does not depend on the index");
            var vm = CompilerTestVm.pv11("Java");
            var one = Term.const_(Constant.integer(1));
            var bare = vm.evaluate(Program.plutusV3(one), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), null, EvalOptions.DEFAULT).budgetConsumed();
            var let = vm.evaluate(Program.plutusV3(Term.apply(Term.lam("v", Term.var(1)), one)),
                    CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), null, EvalOptions.DEFAULT).budgetConsumed();
            // Whole-program check of the slopes: two index-0 sites on lists of 2 and 8 elements
            // differ by the ListToArray slope; sites (0, 1) versus (0, 0) by the traversal step.
            long delta2at2 = measuredDelta(2, List.of(0, 0));
            long delta2at8 = measuredDelta(8, List.of(0, 0));
            assertEquals(6 * l2aStep, delta2at8 - delta2at2, "length slope of a promoted program is the ListToArray slope");
            assertEquals(-step, measuredDelta(8, List.of(0, 1)) - delta2at8, "index slope of a promoted program is the traversal step");
            return new CostModel(get0, step, l2a[0], l2aStep, site0, let.cpuSteps() - bare.cpuSteps(),
                    l2aMemory[0], l2aMemoryStep, let.memoryUnits() - bare.memoryUnits());
        }

        /** The safe profile is the "before" of every promotion, so constants are measured there. */
        private static Program direct(PirTerm term) {
            return compileDirect(term, OptimizationLevel.PV11_SAFE);
        }

        private static ExBudget budget(Program program) {
            return evaluate(program, List.of(), "Java").budgetConsumed();
        }
    }

    // --- helpers ---

    private static PirTerm get(PirTerm list, long index) {
        return get(list, new PirTerm.Const(Constant.integer(index)));
    }

    /** Alias used inside {@link CostModel}, where {@code get} names the accessor. */
    private static PirTerm getAt(PirTerm list, long index) {
        return get(list, index);
    }

    private static PirTerm app2(DefaultFun fun, PirTerm a, PirTerm b) {
        return new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(fun), a), b);
    }

    private static PirTerm get(PirTerm list, PirTerm index) {
        return unI(PirHelpers.recursiveListGet(list, index));
    }

    private static PirTerm unI(PirTerm term) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), term);
    }

    private static PirTerm add(PirTerm a, PirTerm b) {
        return new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), a), b);
    }

    private static PirTerm listToArray(PirTerm list) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.ListToArray), list);
    }

    /** A list of integers as a runtime list (decoded from a Data constant). */
    private static PirTerm list(PlutusData data) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), new PirTerm.Const(Constant.data(data)));
    }

    private static PirTerm lower(PirTerm term, OptimizationLevel level) {
        return new ListIndexPromotionPass(context(level), Map.of()).lower(term).term();
    }

    private static CompilationContext context(OptimizationLevel level) {
        return CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(level).setOptimizationCostProfile(PROFILE));
    }

    private static Program compileDirect(PirTerm term, OptimizationLevel level) {
        return new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(level).setOptimizationCostProfile(PROFILE))
                .compilePirToProgram(term);
    }

    private static long cpu(Program program, String provider) {
        return evaluate(program, List.of(), provider).budgetConsumed().cpuSteps();
    }

    /** The Java-VM integer result at the costed profile, after asserting equivalence with the safe profile. */
    private static long integerResult(PirTerm term, int arrays) {
        assertEquivalent(term, arrays);
        var result = assertInstanceOf(EvalResult.Success.class, evaluate(compileDirect(term, OptimizationLevel.PV11_COSTED), List.of(), "Java"));
        return ((Constant.IntegerConst) ((Term.Const) result.resultTerm()).value()).value().longValueExact();
    }

    /** Safe versus costed on the three providers: same outcome, traces and result; failures up to the O9 substitution. */
    private static void assertEquivalent(PirTerm term, int arrays) {
        var safe = compileDirect(term, OptimizationLevel.PV11_SAFE);
        var costed = compileDirect(term, OptimizationLevel.PV11_COSTED);
        assertEquals(arrays, countArrayBindings(lower(term, OptimizationLevel.PV11_COSTED)));
        EvalResult javaResult = null;
        for (String provider : PROVIDERS) {
            var before = evaluate(safe, List.of(), provider);
            var after = evaluate(costed, List.of(), provider);
            assertEquals(before.getClass(), after.getClass(), provider);
            assertEquals(before.traces(), after.traces(), provider);
            if (before instanceof EvalResult.Success b) assertEquals(b.resultTerm(), ((EvalResult.Success) after).resultTerm(), provider);
            if (before instanceof EvalResult.Failure b) IndexFailureEquivalence.assertFailureTextEquivalent(b, (EvalResult.Failure) after, provider);
            if (provider.equals("Java")) javaResult = after;
            if (!provider.equals("Java")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), provider);
        }
    }

    static int countRecursiveGets(PirTerm term) {
        int[] count = {0};
        walk(term, t -> { if (t instanceof PirTerm.LetRec rec && rec.bindings().equals(List.of(PirHelpers.RECURSIVE_LIST_GET))) count[0]++; });
        return count[0];
    }

    static int countArrayBindings(PirTerm term) {
        int[] count = {0};
        walk(term, t -> { if (t instanceof PirTerm.Let let && let.name().startsWith("#array-")) count[0]++; });
        return count[0];
    }

    private static int countIndexArray(PirTerm term) {
        int[] count = {0};
        walk(term, t -> { if (t instanceof PirTerm.App app && app.function() instanceof PirTerm.App inner
                && inner.function() instanceof PirTerm.Builtin b && b.fun() == DefaultFun.IndexArray) count[0]++; });
        return count[0];
    }

    private static boolean containsBuiltin(PirTerm term, DefaultFun fun) {
        boolean[] found = {false};
        walk(term, t -> { if (t instanceof PirTerm.Builtin b && b.fun() == fun) found[0] = true; });
        return found[0];
    }

    private static void walk(PirTerm term, Consumer<PirTerm> visit) {
        visit.accept(term);
        switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> { }
            case PirTerm.Lam l -> walk(l.body(), visit);
            case PirTerm.Let l -> { walk(l.value(), visit); walk(l.body(), visit); }
            case PirTerm.LetRec r -> { r.bindings().forEach(b -> walk(b.value(), visit)); walk(r.body(), visit); }
            case PirTerm.App a -> { walk(a.function(), visit); walk(a.argument(), visit); }
            case PirTerm.IfThenElse i -> { walk(i.cond(), visit); walk(i.thenBranch(), visit); walk(i.elseBranch(), visit); }
            case PirTerm.Trace t -> { walk(t.message(), visit); walk(t.body(), visit); }
            case PirTerm.DataConstr c -> c.fields().forEach(f -> walk(f, visit));
            case PirTerm.DataMatch m -> { walk(m.scrutinee(), visit); m.branches().forEach(b -> walk(b.body(), visit)); }
            case PirTerm.ListMatch m -> { walk(m.scrutinee(), visit); walk(m.nilBranch(), visit); walk(m.consBranch(), visit); }
            case PirTerm.PairMatch m -> { walk(m.scrutinee(), visit); walk(m.body(), visit); }
            case PirTerm.IntegerCase c -> { walk(c.scrutinee(), visit); c.branches().forEach(b -> walk(b, visit)); }
        }
    }

    static CompileResult compile(String source, String method, OptimizationLevel level, boolean maps) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setOptimizationLevel(level).setSourceMapEnabled(maps).setOptimizationCostProfile(PROFILE))
                .compileMethod(source, method);
    }

    private static EvalResult evaluate(Program program, List<PlutusData> args, String provider) {
        var vm = CompilerTestVm.pv11(provider);
        if (provider.equals("Scalus")) {
            return args.isEmpty() ? vm.evaluate(program) : vm.evaluateWithArgs(program, args);
        }
        return args.isEmpty()
                ? vm.evaluate(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), null, EvalOptions.DEFAULT)
                : vm.evaluateWithArgs(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), args, null, EvalOptions.DEFAULT);
    }

    private static Program golden(int fixture, OptimizationLevel level, boolean maps) throws IOException {
        String id = fixture + "-" + level + "-" + maps;
        try (var input = O9ListIndexPromotionTest.class.getResourceAsStream("/optimization/o9-pre-change-bytes.txt")) {
            assertNotNull(input);
            String hex = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(id + " ")).findFirst()
                    .orElseThrow(() -> new AssertionError("missing golden row " + id
                            + " in optimization/o9-pre-change-bytes.txt; recapture from the base commit"))
                    .substring(id.length() + 1);
            return UplcFlatDecoder.decodeProgram(HexFormat.of().parseHex(hex));
        }
    }
}
