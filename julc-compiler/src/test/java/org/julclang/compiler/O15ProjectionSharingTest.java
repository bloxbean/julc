package org.julclang.compiler;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.pir.ListIndexPromotionPass;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.ValueConversionSharingPass;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.source.SourceLocation;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.OptimizationCostProfile;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.julclang.compiler.O15ProjectionSharingFixtures.BAD_INNER;
import static org.julclang.compiler.O15ProjectionSharingFixtures.BOX_CLOSED;
import static org.julclang.compiler.O15ProjectionSharingFixtures.BOX_OPEN;
import static org.julclang.compiler.O15ProjectionSharingFixtures.EMPTY_RECORD;
import static org.julclang.compiler.O15ProjectionSharingFixtures.FIXTURES;
import static org.julclang.compiler.O15ProjectionSharingFixtures.NOT_A_RECORD;
import static org.julclang.compiler.O15ProjectionSharingFixtures.WALLET_TWO;
import static org.julclang.compiler.O15ProjectionSharingFixtures.txInfo;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-044 (O15): record field projection sharing in the generalised strict-prefix sharing
 * pass. Every fixture in {@link O15ProjectionSharingFixtures} is compiled at every level with
 * source maps off and on and compared with the golden bytes captured at the base commit
 * ({@code optimization/o15-pre-change-bytes.txt}): NONE/BASELINE and non-sharing fixtures stay
 * byte-identical, and the safe profile is observationally equivalent on Java, Truffle and
 * Scalus for every input, including the exact failure text, and never more expensive on a
 * successful path than the binding bound allows. Direct-PIR cases pin the unit classes, the
 * round order, rebinding, dead bindings and the per-rule switches.
 */
@Tag("pair-case-backends")
class O15ProjectionSharingTest {

    private static final OptimizationCostProfile PROFILE = OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11;
    private static final PirType DATA = new PirType.DataType();
    private static final PirType INT = new PirType.IntegerType();
    private static final List<String> PROVIDERS = List.of("Java", "Truffle", "Scalus");
    private static final String HANDOFF = "FIELD_THEN_INDEX";

    @Test
    void safeProfileSharesLeadingProjectionsAndStaysObservationallyEquivalentOnEveryBackend() throws IOException {
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
                    boolean expectRule = level.pv11SafeRulesEnabled() && fixture.shares();
                    var rules = compiled.optimizationReport().appliedRules();
                    assertEquals(expectRule, rules.contains(ValueConversionSharingPass.PROJECTION_RULE), label);
                    assertFalse(rules.contains(ValueConversionSharingPass.RULE), label);
                    var pir = compiled.pirTerm();
                    assertEquals(expectRule ? fixture.fieldBindings() : 0, countLets(pir, "#field-"), label);
                    assertEquals(expectRule ? fixture.prefixBindings() : 0, countLets(pir, "#fields-"), label);
                    assertEquals(expectRule ? fixture.chainsAfter() : fixture.chainsBefore(), countChains(pir), label);
                    walkUser(pir, term -> {
                        if (term instanceof PirTerm.Let let && let.name().startsWith("#field-")) {
                            assertNotNull(chainKey(let.value()), label + ": " + let.value());
                            assertFalse(let.body() instanceof PirTerm.Lam, label + ": shared binding wraps a lambda");
                        } else if (term instanceof PirTerm.Let let && let.name().startsWith("#fields-")) {
                            assertNotNull(prefixRoot(let.value()), label + ": " + let.value());
                            assertFalse(let.body() instanceof PirTerm.Lam, label + ": shared binding wraps a lambda");
                        }
                    });
                    // The O15-to-O9 handoff: a list projection bound once by O15 is a proven list for
                    // ADR-043, so both get sites promote at the costed profile.
                    boolean handoff = fixture.name().equals(HANDOFF) && level.pv11CostedRulesEnabled();
                    assertEquals(handoff, rules.contains(ListIndexPromotionPass.RULE), label);
                    assertEquals(handoff ? 1 : 0, countLets(pir, "#array-"), label);
                    if (handoff) assertEquals(0, countRecursiveGets(pir), label);

                    var old = golden(i, level, maps);
                    var oldBytes = UplcFlatEncoder.encodeProgram(old);
                    // The golden is reproducible at this commit with the rule switched off.
                    assertArrayEquals(oldBytes, UplcFlatEncoder.encodeProgram(
                            compile(fixture.source(), fixture.method(), level, maps, ValueConversionSharingPass.PROJECTION_RULE).program()),
                            label + " with the rule off");
                    if (!expectRule) {
                        assertArrayEquals(oldBytes, bytes, label);
                    } else {
                        // Sharing k sites of a chain of n builtin applications removes (k-1)·n
                        // applications (and their forces) for one lambda, one application and k
                        // variable uses, so a shared program is strictly smaller at the safe level;
                        // at the costed level the O9 handoff may add an array conversion, and
                        // source-map builds skip the UPLC optimiser.
                        if (!maps && level == OptimizationLevel.PV11_SAFE) {
                            assertTrue(bytes.length < oldBytes.length, label + " " + oldBytes.length + " -> " + bytes.length);
                        } else if (!maps) {
                            assertTrue(bytes.length <= oldBytes.length + 1, label + " " + oldBytes.length + " -> " + bytes.length);
                        }
                        assertNotEquals(JulcScriptAdapter.scriptHash(old), JulcScriptAdapter.scriptHash(program), label);
                    }
                    for (var input : fixture.inputs()) {
                        EvalResult javaResult = null;
                        for (String provider : PROVIDERS) {
                            String inputLabel = label + "/" + provider + "/" + input;
                            var before = evaluate(old, input.args(), provider);
                            var after = evaluate(program, input.args(), provider);
                            assertEquals(input.success(), before.isSuccess(), inputLabel + " " + before);
                            assertEquals(input.success(), after.isSuccess(), inputLabel + " " + after);
                            assertEquals(before.getClass(), after.getClass(), inputLabel);
                            assertEquals(before.traces(), after.traces(), inputLabel);
                            if (before instanceof EvalResult.Success b) {
                                assertEquals(b.resultTerm(), ((EvalResult.Success) after).resultTerm(), inputLabel);
                            } else if (before instanceof EvalResult.Failure b) {
                                var a = (EvalResult.Failure) after;
                                if (handoff && input.index() != null) {
                                    IndexFailureEquivalence.assertPromotedFailure(b, a, input.index(), input.size(), inputLabel);
                                } else {
                                    // O15 is failure-text neutral: the same builtin fails on the same input.
                                    assertEquals(b.error(), a.error(), inputLabel);
                                }
                            }
                            if (provider.equals("Java")) javaResult = after;
                            // Truffle and Scalus charge exactly what the Java VM charges.
                            if (!provider.equals("Java")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), inputLabel);
                            if (level == OptimizationLevel.PV11_SAFE && !maps && provider.equals("Java")) {
                                System.out.println("PROJECTION_SHARING_COST " + fixture.name() + " " + input + " "
                                        + before.budgetConsumed() + " -> " + after.budgetConsumed());
                            }
                            if (expectRule && !handoff && input.success() && provider.equals("Java")) {
                                // A path that reaches a later occurrence saves that projection; a path
                                // that reaches only the leading one pays the binding: one lambda, one
                                // application and one variable lookup per shared binding on the path.
                                var bound = bindingOverhead();
                                int bindings = fixture.fieldBindings() + fixture.prefixBindings();
                                assertTrue(after.budgetConsumed().cpuSteps()
                                        <= before.budgetConsumed().cpuSteps() + bindings * bound.cpuSteps(), inputLabel
                                        + " " + before.budgetConsumed() + " -> " + after.budgetConsumed());
                                assertTrue(after.budgetConsumed().memoryUnits()
                                        <= before.budgetConsumed().memoryUnits() + bindings * bound.memoryUnits(), inputLabel
                                        + " " + before.budgetConsumed() + " -> " + after.budgetConsumed());
                            }
                        }
                    }
                    if (level == OptimizationLevel.PV11_SAFE && !maps) {
                        System.out.println("PROJECTION_SHARING_ARTIFACT " + fixture.name() + " "
                                + oldBytes.length + " -> " + bytes.length + " "
                                + JulcScriptAdapter.scriptHash(old) + " -> " + JulcScriptAdapter.scriptHash(program));
                    }
                }
            }
        }
    }

    @Test
    void safeProfileOutputIsByteIdenticalToTheManualBindingSource() {
        var repeated = FIXTURES.get(0);
        var manual = FIXTURES.get(1);
        assertEquals("REPEATED", repeated.name());
        assertEquals("MANUAL", manual.name());
        for (var level : List.of(OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED)) {
            for (boolean maps : List.of(false, true)) {
                var automatic = compile(repeated.source(), repeated.method(), level, maps);
                var byHand = compile(manual.source(), manual.method(), level, maps);
                assertArrayEquals(UplcFlatEncoder.encodeProgram(byHand.program()),
                        UplcFlatEncoder.encodeProgram(automatic.program()), level + "/" + maps);
                assertTrue(automatic.optimizationReport().appliedRules().contains(ValueConversionSharingPass.PROJECTION_RULE));
                assertFalse(byHand.optimizationReport().appliedRules().contains(ValueConversionSharingPass.PROJECTION_RULE));
            }
        }
    }

    /**
     * Unit classes, round order, rebinding, dead bindings and the switches, shape by shape on
     * hand-built PIR; the observable cases are also evaluated on all three VMs against the
     * BASELINE program.
     */
    @Test
    void directPirSharingMatchesUnitsRespectsRebindingAndSkipsDeadBindings() {
        var x = new PirTerm.Var("x", DATA);
        var y = new PirTerm.Var("y", DATA);
        var flag = new PirTerm.Var("flag", new PirType.BoolType());

        // 1. The Bool arm is one unit: the shared value is the whole equalsInteger(...) form and
        //    the raw chain inside it is never bound on its own.
        var open = boolField(x, 3);
        var guarded = new PirTerm.IfThenElse(open, new PirTerm.IfThenElse(open, one(), zero()), new PirTerm.IfThenElse(flag, one(), two()));
        var loweredGuarded = assertInstanceOf(PirTerm.Let.class, lower(guarded, OptimizationLevel.PV11_SAFE));
        assertEquals("#field-0", loweredGuarded.name());
        assertEquals(open, loweredGuarded.value());
        assertEquals(1, countChains(loweredGuarded));
        assertEquals(0, countLets(loweredGuarded, "#fields-"));
        assertEquivalent(guarded, BOX_OPEN);
        assertEquivalent(guarded, BOX_CLOSED);
        assertEquivalent(guarded, NOT_A_RECORD);
        assertEquivalent(guarded, EMPTY_RECORD);
        for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE)) {
            var closed = closed(guarded, BOX_OPEN);
            assertSame(closed, lowerClosed(closed, level));
        }

        // 2. Different decode arms over the same raw field are different units and the raw
        //    sub-chain inside a decode is not counted: no field binding appears (a raw-chain unit
        //    would have been bound twice over), only the prefix the two arms still share.
        var mixedArms = add(intField(x, 0), length(bytesRaw(x, 0)));
        var loweredArms = assertInstanceOf(PirTerm.Let.class, lower(mixedArms, OptimizationLevel.PV11_SAFE));
        assertEquals("#fields-0", loweredArms.name());
        assertEquals(0, countLets(loweredArms, "#field-"));
        assertEquals(2, countChains(loweredArms));
        assertEquals(1, count(loweredArms, t -> t instanceof PirTerm.App app && isBuiltin(app.function(), DefaultFun.UnIData)));
        assertEquals(1, count(loweredArms, t -> t instanceof PirTerm.App app && isBuiltin(app.function(), DefaultFun.UnBData)));

        // 3. Two distinct fields once each: only the prefix is shared, and every chain then
        //    roots at the shared fields list.
        var twoFields = add(intField(x, 0), length(bytesRaw(x, 2)));
        var loweredTwo = assertInstanceOf(PirTerm.Let.class, lower(twoFields, OptimizationLevel.PV11_SAFE));
        assertEquals("#fields-0", loweredTwo.name());
        assertEquals(prefix(x), loweredTwo.value());
        assertEquals(0, countPrefixes(loweredTwo.body()));
        assertEquals(2, countChains(loweredTwo));
        assertEquivalent(twoFields, BOX_OPEN);
        assertEquivalent(twoFields, NOT_A_RECORD);
        assertEquivalent(twoFields, BOX_CLOSED);

        // 4. Chain on chain reaches a fixed point: the repeated inner projection is bound, the
        //    outer chains re-root at it, and their shared prefix is bound below.
        var inner = raw(x, 5);
        var nested = add(intFieldOf(inner, 0), intFieldOf(inner, 1));
        var loweredNested = assertInstanceOf(PirTerm.Let.class, lower(nested, OptimizationLevel.PV11_SAFE));
        assertEquals("#field-0", loweredNested.name());
        assertEquals(inner, loweredNested.value());
        var nestedPrefix = assertInstanceOf(PirTerm.Let.class, loweredNested.body());
        assertEquals("#fields-0", nestedPrefix.name());
        assertEquals(prefix(new PirTerm.Var("#field-0", DATA)), nestedPrefix.value());
        assertEquals(3, countChains(loweredNested));
        assertEquivalent(nested, BOX_OPEN);
        assertEquivalent(nested, BAD_INNER);
        assertEquivalent(nested, NOT_A_RECORD);

        // 4b. A matched unit is a leaf for the rewriter too: with a bare raw chain and a decode
        //     of the same field in one scope, the raw binding takes only the bare sites, the
        //     decode is shared as its own unit (one unIData survives), and the prefix common to
        //     both is shared above them; the shape does not depend on candidate order.
        var rawAndDecoded = new PirTerm.Let("a", raw(x, 0), new PirTerm.Let("b", raw(x, 0), add(intField(x, 0), intField(x, 0))));
        var loweredRaw = assertInstanceOf(PirTerm.Let.class, lower(rawAndDecoded, OptimizationLevel.PV11_SAFE));
        assertEquals("#fields-0", loweredRaw.name());
        assertEquals(prefix(x), loweredRaw.value());
        var rawLet = assertInstanceOf(PirTerm.Let.class, loweredRaw.body());
        assertEquals("#field-0", rawLet.name());
        assertEquals(new ChainKey("#fields-0", 0, "raw"), chainKey(rawLet.value()));
        var decodedLet = assertInstanceOf(PirTerm.Let.class, rawLet.body());
        assertEquals("#field-1", decodedLet.name());
        assertEquals(new ChainKey("#fields-0", 0, "UnIData"), chainKey(decodedLet.value()));
        assertEquals(1, count(loweredRaw, t -> t instanceof PirTerm.App app && isBuiltin(app.function(), DefaultFun.UnIData)));
        assertEquals(2, countChains(loweredRaw));
        assertEquals(0, countPrefixes(loweredRaw.body()));
        assertEquivalent(rawAndDecoded, BOX_OPEN);
        assertEquivalent(rawAndDecoded, EMPTY_RECORD);

        // 4c. An error arm never leads: the pair in the other arm is shared inside that arm.
        var errorGuard = new PirTerm.IfThenElse(flag, new PirTerm.Error(INT), add(intField(x, 0), intField(x, 0)));
        var loweredError = assertInstanceOf(PirTerm.IfThenElse.class, lower(errorGuard, OptimizationLevel.PV11_SAFE));
        assertEquals(errorGuard.thenBranch(), loweredError.thenBranch());
        assertEquals("#field-0", assertInstanceOf(PirTerm.Let.class, loweredError.elseBranch()).name());
        assertEquals(1, countChains(loweredError));
        assertEquivalent(errorGuard, BOX_OPEN);

        // 4d. Source positions: the scope's location moves to the inserted binding, each
        //     replaced site's location to the variable that replaced it; the unit keeps its own.
        var siteA = intField(x, 0);
        var siteB = intField(x, 0);
        var scope = add(siteA, siteB);
        var scopeAt = new SourceLocation("Scope.java", 1, 1, "scope");
        var siteAAt = new SourceLocation("Scope.java", 2, 1, "a");
        var siteBAt = new SourceLocation("Scope.java", 3, 1, "b");
        var positions = new IdentityHashMap<PirTerm, SourceLocation>();
        positions.put(scope, scopeAt);
        positions.put(siteA, siteAAt);
        positions.put(siteB, siteBAt);
        var located = new ValueConversionSharingPass(context(OptimizationLevel.PV11_SAFE), positions).lower(closed(scope, BOX_OPEN));
        var locatedLet = assertInstanceOf(PirTerm.Let.class, stripClosing(located.term()));
        assertEquals(scopeAt, located.positions().get(locatedLet));
        assertSame(siteA, locatedLet.value());
        assertEquals(siteAAt, located.positions().get(locatedLet.value()));
        var uses = new java.util.ArrayList<PirTerm>();
        walkUser(locatedLet.body(), t -> { if (t instanceof PirTerm.Var v && v.name().equals("#field-0")) uses.add(t); });
        assertEquals(2, uses.size());
        assertEquals(List.of(siteAAt, siteBAt), uses.stream().map(located.positions()::get).toList());

        // 5. Rebinding: a use under a Let, lambda or pattern binder named x belongs to that binder.
        var underLet = add(intField(x, 0), new PirTerm.Let("x", y, intField(x, 0)));
        assertSame(underLet, lower(underLet, OptimizationLevel.PV11_SAFE));
        var underLam = add(intField(x, 0), new PirTerm.App(new PirTerm.Lam("x", DATA, intField(x, 0)), y));
        assertSame(underLam, lower(underLam, OptimizationLevel.PV11_SAFE));
        var underMatch = add(intField(x, 0), new PirTerm.DataMatch(y, List.of(
                new PirTerm.MatchBranch("Only", List.of("x"), List.of(DATA), intField(x, 0), null))));
        assertSame(underMatch, lower(underMatch, OptimizationLevel.PV11_SAFE));
        var leadingThenRebound = add(intField(x, 0), add(intField(x, 0), new PirTerm.Let("x", y, intField(x, 0))));
        var loweredRebound = assertInstanceOf(PirTerm.Let.class, lower(leadingThenRebound, OptimizationLevel.PV11_SAFE));
        assertEquals(2, countChains(loweredRebound));
        assertEquivalent(leadingThenRebound, BOX_OPEN);

        // 6. A lambda binding the live body never calls is dead, transitively: units inside it
        //    are neither counted nor rewritten and no provenance is recorded. Once called, its
        //    body is shared like any scope.
        var twice = new PirTerm.Lam("d", DATA, add(intField(new PirTerm.Var("d", DATA), 0), intField(new PirTerm.Var("d", DATA), 0)));
        var deadHelper = new PirTerm.Let("helper", twice, intField(x, 0));
        var deadContext = context(OptimizationLevel.PV11_SAFE);
        var closedDead = closed(deadHelper, BOX_OPEN);
        assertSame(closedDead, lowerWith(deadContext, closedDead), "dead helper rewritten");
        assertFalse(deadContext.optimizationReport().appliedRules().contains(ValueConversionSharingPass.PROJECTION_RULE));
        var transitivelyDead = new PirTerm.Let("first", twice,
                new PirTerm.Let("second", new PirTerm.Lam("e", DATA, new PirTerm.App(new PirTerm.Var("first", DATA), new PirTerm.Var("e", DATA))),
                        intField(x, 0)));
        assertSame(transitivelyDead, lower(transitivelyDead, OptimizationLevel.PV11_SAFE));
        var deadLoop = new PirTerm.LetRec(List.of(new PirTerm.Binding("loop", twice)), intField(x, 0));
        assertSame(deadLoop, lower(deadLoop, OptimizationLevel.PV11_SAFE));
        // A single recursive binding of a lambda is a trivial prefix (its body can lead); the
        // multi-binding lowering is not claimed and blocks.
        var callLoop = new PirTerm.App(new PirTerm.Var("loop", DATA), intField(x, 0));
        var singleRecursive = add(new PirTerm.LetRec(List.of(new PirTerm.Binding("loop", twice)), callLoop), intField(x, 0));
        var loweredSingle = assertInstanceOf(PirTerm.Let.class, lower(singleRecursive, OptimizationLevel.PV11_SAFE));
        assertEquals("#field-0", loweredSingle.name());
        assertEquivalent(singleRecursive, BOX_OPEN);
        var mutual = add(new PirTerm.LetRec(List.of(new PirTerm.Binding("loop", twice),
                new PirTerm.Binding("other", new PirTerm.Lam("e", DATA, new PirTerm.App(new PirTerm.Var("loop", DATA), new PirTerm.Var("e", DATA))))),
                callLoop), intField(x, 0));
        var loweredMutual = assertInstanceOf(PirTerm.App.class, lower(mutual, OptimizationLevel.PV11_SAFE));
        // Only the live lambda's own pair of d projections is shared; the two x chains stay.
        assertEquals(1, countLets(loweredMutual, "#field-"));
        assertEquals(3, countChains(loweredMutual));
        assertEquivalent(mutual, BOX_OPEN);
        var transitivelyDeadContext = context(OptimizationLevel.PV11_SAFE);
        lowerWith(transitivelyDeadContext, closed(transitivelyDead, BOX_OPEN));
        assertFalse(transitivelyDeadContext.optimizationReport().appliedRules().contains(ValueConversionSharingPass.PROJECTION_RULE));
        var deadLoopContext = context(OptimizationLevel.PV11_SAFE);
        lowerWith(deadLoopContext, closed(deadLoop, BOX_OPEN));
        assertFalse(deadLoopContext.optimizationReport().appliedRules().contains(ValueConversionSharingPass.PROJECTION_RULE));
        var liveHelper = new PirTerm.Let("helper", twice, new PirTerm.App(new PirTerm.Var("helper", DATA), x));
        var liveContext = context(OptimizationLevel.PV11_SAFE);
        var loweredLive = assertInstanceOf(PirTerm.Let.class, stripClosing(
                new ValueConversionSharingPass(liveContext, Map.of()).lower(closed(liveHelper, BOX_OPEN)).term()));
        assertInstanceOf(PirTerm.Let.class, assertInstanceOf(PirTerm.Lam.class, loweredLive.value()).body());
        assertTrue(liveContext.optimizationReport().appliedRules().contains(ValueConversionSharingPass.PROJECTION_RULE));
        assertEquivalent(liveHelper, BOX_OPEN);

        // 7. Round order: a projection shared by O15 is a variable, so a repeated native Value
        //    conversion of it is shared by O8 afterwards; each switch removes exactly its class.
        var valueTwice = add(unValue(raw(x, 1)), unValue(raw(x, 1)));
        var bothContext = context(OptimizationLevel.PV11_SAFE);
        var loweredBoth = assertInstanceOf(PirTerm.Let.class, stripClosing(
                new ValueConversionSharingPass(bothContext, Map.of()).lower(closed(valueTwice, BOX_OPEN)).term()));
        assertEquals("#field-0", loweredBoth.name());
        var valueLet = assertInstanceOf(PirTerm.Let.class, loweredBoth.body());
        assertEquals("#value-0", valueLet.name());
        assertEquals(unValue(new PirTerm.Var("#field-0", DATA)), valueLet.value());
        assertTrue(bothContext.optimizationReport().appliedRules().containsAll(
                List.of(ValueConversionSharingPass.PROJECTION_RULE, ValueConversionSharingPass.RULE)));
        var noProjections = context(OptimizationLevel.PV11_SAFE, ValueConversionSharingPass.PROJECTION_RULE);
        var closedValueTwice = closed(valueTwice, BOX_OPEN);
        assertSame(closedValueTwice, lowerWith(noProjections, closedValueTwice));
        assertTrue(noProjections.optimizationReport().appliedRules().isEmpty());
        var noValues = context(OptimizationLevel.PV11_SAFE, ValueConversionSharingPass.RULE);
        var loweredNoValues = assertInstanceOf(PirTerm.Let.class, stripClosing(lowerWith(noValues, closed(valueTwice, BOX_OPEN))));
        assertEquals("#field-0", loweredNoValues.name());
        assertEquals(2, count(loweredNoValues.body(), t -> t instanceof PirTerm.App app
                && app.function() instanceof PirTerm.Builtin b && b.fun() == DefaultFun.UnValueData));
        assertEquals(List.of(ValueConversionSharingPass.PROJECTION_RULE), noValues.optimizationReport().appliedRules());
    }

    /**
     * The budget model, measured from the pinned profile and asserted exactly on hand-built PIR
     * without the UPLC optimiser (source maps on): sharing a unit at {@code k} sites costs one
     * lambda, one application and {@code k} variable lookups and saves {@code k - 1} evaluations
     * of the unit. Unit costs are printed for the evidence document.
     */
    @Test
    void sharingCostDerivesFromThePinnedProfile() {
        var x = new PirTerm.Var("x", DATA);
        var overhead = bindingOverhead();
        long step = overhead.cpuSteps() / 3;
        long stepMemory = overhead.memoryUnits() / 3;
        assertEquals(overhead.cpuSteps(), 3 * step);
        assertEquals(overhead.memoryUnits(), 3 * stepMemory);
        record Shape(String name, List<PirTerm> sites, PirTerm unit, PlutusData input) {}
        var shapes = List.of(
                new Shape("integer-depth-0", List.of(intField(x, 0), intField(x, 0), intField(x, 0)), intField(x, 0), BOX_OPEN),
                new Shape("raw-depth-5", List.of(raw(x, 5), raw(x, 5)), raw(x, 5), BOX_OPEN),
                new Shape("list-depth-1", List.of(listField(x, 1), listField(x, 1)), listField(x, 1), BOX_OPEN),
                new Shape("bool-depth-3", List.of(boolField(x, 3), boolField(x, 3)), boolField(x, 3), BOX_OPEN),
                new Shape("bytes-depth-2", List.of(bytesRaw(x, 2), bytesRaw(x, 2)), bytesRaw(x, 2), BOX_OPEN),
                new Shape("map-depth-0", List.of(mapField(x, 0), mapField(x, 0)), mapField(x, 0), WALLET_TWO),
                new Shape("prefix", List.of(intField(x, 0), bytesRaw(x, 2), raw(x, 5)), prefix(x), BOX_OPEN));
        for (var shape : shapes) {
            // The unit's own cost including the lookup of its root variable.
            var unitCost = budget(unoptimized(closed(shape.unit(), shape.input()), OptimizationLevel.BASELINE));
            var rootCost = budget(unoptimized(closed(x, shape.input()), OptimizationLevel.BASELINE));
            long unitCpu = unitCost.cpuSteps() - rootCost.cpuSteps() + step;
            long unitMemory = unitCost.memoryUnits() - rootCost.memoryUnits() + stepMemory;
            System.out.println("PROJECTION_SHARING_UNIT " + shape.name() + " cpu=" + unitCpu + " mem=" + unitMemory);
            for (int sites = 2; sites <= shape.sites().size(); sites++) {
                PirTerm body = zero();
                for (int i = sites - 1; i >= 0; i--) body = new PirTerm.Let("site" + i, shape.sites().get(i), body);
                var before = budget(unoptimized(closed(body, shape.input()), OptimizationLevel.BASELINE));
                var after = budget(unoptimized(closed(body, shape.input()), OptimizationLevel.PV11_SAFE));
                String label = shape.name() + "/" + sites;
                assertEquals(before.cpuSteps() + (2 + sites) * step - (sites - 1) * unitCpu, after.cpuSteps(), label);
                assertEquals(before.memoryUnits() + (2 + sites) * stepMemory - (sites - 1) * unitMemory, after.memoryUnits(), label);
            }
        }
    }

    /**
     * The validator shape from the example corpus. Root-level projections of the entrypoint's
     * own record parameters are already bound once by the strict boundary, so nothing is shared
     * twice; the nested {@code txInfo} projections are shared, and the validator's outcome on a
     * real script context is unchanged on every backend.
     */
    @Test
    void validatorSharesNestedContextProjectionsAndKeepsItsOutcome() {
        var source = """
                import org.julclang.ledger.ScriptContext;
                import org.julclang.ledger.TxInfo;
                import java.math.BigInteger;
                @MintingValidator
                class Corpus {
                    record Order(BigInteger amount, byte[] owner) {}
                    @Entrypoint
                    static boolean validate(Order order, ScriptContext ctx) {
                        if (ctx.txInfo().outputs().isEmpty()) {
                            return order.amount().signum() == 0;
                        }
                        BigInteger count = BigInteger.valueOf(ctx.txInfo().outputs().size());
                        return count.compareTo(order.amount()) <= 0 && ctx.txInfo().fee().signum() > 0;
                    }
                }
                """;
        var shared = compileValidator(source, true);
        var unshared = compileValidator(source, false);
        assertFalse(shared.hasErrors(), shared.diagnostics().toString());
        assertTrue(shared.optimizationReport().appliedRules().contains(ValueConversionSharingPass.PROJECTION_RULE));
        assertFalse(unshared.optimizationReport().appliedRules().contains(ValueConversionSharingPass.PROJECTION_RULE));
        var pir = shared.pirTerm();
        assertNotNull(pir);
        assertTrue(countLets(pir, "#field-") >= 1, "nested txInfo projections shared");
        walkUser(pir, term -> {
            if (term instanceof PirTerm.Let let && (let.name().startsWith("#field-") || let.name().startsWith("#fields-"))) {
                assertNotEquals("order", rootOf(let.value()), "the boundary already binds root projections: " + let.value());
            }
        });
        var twoOutputs = txInfo(PlutusData.constr(0), PlutusData.constr(0));
        var order = PlutusData.constr(0, PlutusData.integer(2), PlutusData.bytes(new byte[28]));
        var zeroOrder = PlutusData.constr(0, PlutusData.integer(0), PlutusData.bytes(new byte[28]));
        for (var input : List.of(
                Map.entry("two-outputs", context(twoOutputs, order)),
                Map.entry("no-outputs", context(txInfo(), zeroOrder)),
                Map.entry("no-outputs-nonzero", context(txInfo(), order)),
                Map.entry("malformed-tx-info", context(PlutusData.integer(1), order)))) {
            EvalResult javaResult = null;
            for (String provider : PROVIDERS) {
                String label = input.getKey() + "/" + provider;
                var before = evaluate(unshared.program(), List.of(input.getValue()), provider);
                var after = evaluate(shared.program(), List.of(input.getValue()), provider);
                assertEquals(before.getClass(), after.getClass(), label);
                assertEquals(before.traces(), after.traces(), label);
                if (before instanceof EvalResult.Success b) assertEquals(b.resultTerm(), ((EvalResult.Success) after).resultTerm(), label);
                if (before instanceof EvalResult.Failure b) assertEquals(b.error(), ((EvalResult.Failure) after).error(), label);
                if (provider.equals("Java")) javaResult = after;
                if (!provider.equals("Java")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), label);
                if (provider.equals("Java")) {
                    System.out.println("PROJECTION_SHARING_VALIDATOR " + input.getKey() + " " + before.budgetConsumed() + " -> " + after.budgetConsumed());
                }
            }
        }
        System.out.println("PROJECTION_SHARING_VALIDATOR_ARTIFACT " + UplcFlatEncoder.encodeProgram(unshared.program()).length
                + " -> " + UplcFlatEncoder.encodeProgram(shared.program()).length);
    }

    // --- PIR builders ---

    static PirTerm prefix(PirTerm root) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), root));
    }

    static PirTerm raw(PirTerm root, int index) {
        PirTerm current = prefix(root);
        for (int i = 0; i < index; i++) current = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), current);
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), current);
    }

    static PirTerm intField(PirTerm root, int index) {
        return PirHelpers.wrapDecode(raw(root, index), INT);
    }

    /** An integer field of a record that is itself a projection (chain on chain). */
    static PirTerm intFieldOf(PirTerm record, int index) {
        return PirHelpers.wrapDecode(raw(record, index), INT);
    }

    static PirTerm bytesRaw(PirTerm root, int index) {
        return PirHelpers.wrapDecode(raw(root, index), new PirType.ByteStringType());
    }

    static PirTerm listField(PirTerm root, int index) {
        return PirHelpers.wrapDecode(raw(root, index), new PirType.ListType(INT));
    }

    /** The program of a closed PIR term with the UPLC optimiser skipped (source maps on). */
    private static Program unoptimized(PirTerm closedTerm, OptimizationLevel level) {
        return new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(true))
                .compilePirToProgram(closedTerm);
    }

    private static ExBudget budget(Program program) {
        var result = evaluate(program, List.of(), "Java");
        assertInstanceOf(EvalResult.Success.class, result, result.toString());
        return result.budgetConsumed();
    }

    static PirTerm boolField(PirTerm root, int index) {
        return PirHelpers.wrapDecode(raw(root, index), new PirType.BoolType());
    }

    static PirTerm mapField(PirTerm root, int index) {
        return PirHelpers.wrapDecode(raw(root, index), new PirType.MapType(INT, INT));
    }

    static PirTerm unValue(PirTerm data) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnValueData), data);
    }

    static PirTerm length(PirTerm bytes) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.LengthOfByteString), bytes);
    }

    static PirTerm add(PirTerm a, PirTerm b) {
        return new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), a), b);
    }

    static PirTerm zero() { return new PirTerm.Const(Constant.integer(BigInteger.ZERO)); }
    static PirTerm one() { return new PirTerm.Const(Constant.integer(BigInteger.ONE)); }
    static PirTerm two() { return new PirTerm.Const(Constant.integer(BigInteger.TWO)); }

    /** Close a body over {@code flag}, {@code y} and the Data input {@code x}. */
    private static PirTerm closed(PirTerm body, PlutusData x) {
        return new PirTerm.Let("flag", new PirTerm.Const(Constant.bool(true)),
                new PirTerm.Let("y", new PirTerm.Const(Constant.data(BOX_CLOSED)),
                new PirTerm.Let("x", new PirTerm.Const(Constant.data(x)), body)));
    }

    private static final List<String> CLOSING = List.of("flag", "y", "x");

    private static PirTerm stripClosing(PirTerm lowered) {
        while (lowered instanceof PirTerm.Let let && CLOSING.contains(let.name())) lowered = let.body();
        return lowered;
    }

    /** Run the pass on the closed program and strip the closing lets to expose the rewritten body. */
    private static PirTerm lower(PirTerm body, OptimizationLevel level) {
        return stripClosing(lowerClosed(closed(body, BOX_OPEN), level));
    }

    private static PirTerm lowerClosed(PirTerm closedTerm, OptimizationLevel level) {
        return lowerWith(context(level), closedTerm);
    }

    private static PirTerm lowerWith(CompilationContext context, PirTerm closedTerm) {
        return new ValueConversionSharingPass(context, Map.of()).lower(closedTerm).term();
    }

    private static CompilationContext context(OptimizationLevel level, String... disabledRules) {
        var options = new CompilerOptions().setOptimizationLevel(level).setOptimizationCostProfile(PROFILE);
        for (var rule : disabledRules) options.disableOptimizationRule(rule);
        return CompilationContext.resolve(options);
    }

    /** BASELINE and safe-profile programs of the closed body agree on result, traces, failure text and class. */
    private static void assertEquivalent(PirTerm body, PlutusData x) {
        var baseline = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE))
                .compilePirToProgram(closed(body, x));
        var safe = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE))
                .compilePirToProgram(closed(body, x));
        EvalResult javaResult = null;
        for (String provider : PROVIDERS) {
            var before = evaluate(baseline, List.of(), provider);
            var after = evaluate(safe, List.of(), provider);
            assertEquals(before.getClass(), after.getClass(), provider + " " + before + " vs " + after);
            assertEquals(before.traces(), after.traces(), provider);
            if (before instanceof EvalResult.Success b) assertEquals(b.resultTerm(), ((EvalResult.Success) after).resultTerm(), provider);
            if (before instanceof EvalResult.Failure b) assertEquals(b.error(), ((EvalResult.Failure) after).error(), provider);
            if (provider.equals("Java")) javaResult = after;
            if (!provider.equals("Java")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), provider);
        }
    }

    // --- observation helpers over the emitted PIR ---
    // These matchers restate the pass's unit grammar from the ADR, so a shared misreading would
    // make the structural counts agree with the pass; the three-VM result, trace, failure-text
    // and budget assertions, and the golden bytes, carry the semantic weight. Library bindings
    // are recognised by their fully qualified names, not by the pass's liveness notion: a live
    // library helper's inside is invisible to the counts but visible to provenance.

    record ChainKey(String root, int depth, String arm) {}

    /** {@code x} when {@code term} is {@code sndPair(unConstrData(x))} or a shared fields variable, else null. */
    static String prefixRoot(PirTerm term) {
        if (term instanceof PirTerm.Var v && v.name().startsWith("#fields-")) return v.name();
        return term instanceof PirTerm.App snd && isBuiltin(snd.function(), DefaultFun.SndPair)
                && snd.argument() instanceof PirTerm.App constr && isBuiltin(constr.function(), DefaultFun.UnConstrData)
                && constr.argument() instanceof PirTerm.Var root ? root.name() : null;
    }

    /** The field chain {@code term} is, outermost decode arm first, or null. */
    static ChainKey chainKey(PirTerm term) {
        if (term instanceof PirTerm.App eq && eq.argument().equals(one())
                && eq.function() instanceof PirTerm.App tag && isBuiltin(tag.function(), DefaultFun.EqualsInteger)
                && tag.argument() instanceof PirTerm.App fst && isBuiltin(fst.function(), DefaultFun.FstPair)
                && fst.argument() instanceof PirTerm.App constr && isBuiltin(constr.function(), DefaultFun.UnConstrData)) {
            var key = rawKey(constr.argument(), "bool");
            if (key != null) return key;
        }
        if (term instanceof PirTerm.App decode && isBuiltin(decode.function(), DefaultFun.DecodeUtf8)
                && decode.argument() instanceof PirTerm.App bytes && isBuiltin(bytes.function(), DefaultFun.UnBData)) {
            var key = rawKey(bytes.argument(), "string");
            if (key != null) return key;
        }
        if (term instanceof PirTerm.App app && app.function() instanceof PirTerm.Builtin b
                && List.of(DefaultFun.UnIData, DefaultFun.UnBData, DefaultFun.UnListData, DefaultFun.UnMapData).contains(b.fun())) {
            var key = rawKey(app.argument(), b.fun().name());
            if (key != null) return key;
        }
        return rawKey(term, "raw");
    }

    private static ChainKey rawKey(PirTerm term, String arm) {
        if (!(term instanceof PirTerm.App head && isBuiltin(head.function(), DefaultFun.HeadList))) return null;
        int depth = 0;
        PirTerm current = head.argument();
        while (current instanceof PirTerm.App tail && isBuiltin(tail.function(), DefaultFun.TailList)) {
            depth++;
            current = tail.argument();
        }
        String root = prefixRoot(current);
        return root == null ? null : new ChainKey(root, depth, arm);
    }

    static String rootOf(PirTerm unit) {
        var key = chainKey(unit);
        if (key != null) return key.root();
        return prefixRoot(unit);
    }

    private static boolean isBuiltin(PirTerm term, DefaultFun fun) {
        return term instanceof PirTerm.Builtin b && b.fun() == fun;
    }

    /** Field chains in user code, a matched chain counted once as a leaf. */
    static int countChains(PirTerm term) {
        int[] count = {0};
        walkUserLeaves(term, t -> chainKey(t) != null, t -> { if (chainKey(t) != null) count[0]++; });
        return count[0];
    }

    /** {@code sndPair(unConstrData(x))} occurrences in user code. */
    static int countPrefixes(PirTerm term) {
        return count(term, t -> !(t instanceof PirTerm.Var) && prefixRoot(t) != null);
    }

    static int countLets(PirTerm term, String prefix) {
        return count(term, t -> t instanceof PirTerm.Let let && let.name().startsWith(prefix));
    }

    static int countRecursiveGets(PirTerm term) {
        return count(term, t -> t instanceof PirTerm.LetRec rec && rec.bindings().equals(List.of(PirHelpers.RECURSIVE_LIST_GET)));
    }

    private static int count(PirTerm term, java.util.function.Predicate<PirTerm> match) {
        int[] count = {0};
        walkUser(term, t -> { if (match.test(t)) count[0]++; });
        return count[0];
    }

    /** Visit user code: the values of library bindings (fully qualified names) are skipped. */
    static void walkUser(PirTerm term, Consumer<PirTerm> visit) {
        walkUserLeaves(term, t -> false, visit);
    }

    private static void walkUserLeaves(PirTerm term, java.util.function.Predicate<PirTerm> leaf, Consumer<PirTerm> visit) {
        visit.accept(term);
        if (leaf.test(term)) return;
        switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> { }
            case PirTerm.Lam l -> walkUserLeaves(l.body(), leaf, visit);
            case PirTerm.Let l -> {
                if (!l.name().contains(".")) walkUserLeaves(l.value(), leaf, visit);
                walkUserLeaves(l.body(), leaf, visit);
            }
            case PirTerm.LetRec r -> {
                r.bindings().forEach(b -> { if (!b.name().contains(".")) walkUserLeaves(b.value(), leaf, visit); });
                walkUserLeaves(r.body(), leaf, visit);
            }
            case PirTerm.App a -> { walkUserLeaves(a.function(), leaf, visit); walkUserLeaves(a.argument(), leaf, visit); }
            case PirTerm.IfThenElse i -> {
                walkUserLeaves(i.cond(), leaf, visit);
                walkUserLeaves(i.thenBranch(), leaf, visit);
                walkUserLeaves(i.elseBranch(), leaf, visit);
            }
            case PirTerm.Trace t -> { walkUserLeaves(t.message(), leaf, visit); walkUserLeaves(t.body(), leaf, visit); }
            case PirTerm.DataConstr c -> c.fields().forEach(f -> walkUserLeaves(f, leaf, visit));
            case PirTerm.DataMatch m -> { walkUserLeaves(m.scrutinee(), leaf, visit); m.branches().forEach(b -> walkUserLeaves(b.body(), leaf, visit)); }
            case PirTerm.ListMatch m -> {
                walkUserLeaves(m.scrutinee(), leaf, visit);
                walkUserLeaves(m.nilBranch(), leaf, visit);
                walkUserLeaves(m.consBranch(), leaf, visit);
            }
            case PirTerm.PairMatch m -> { walkUserLeaves(m.scrutinee(), leaf, visit); walkUserLeaves(m.body(), leaf, visit); }
            case PirTerm.IntegerCase c -> { walkUserLeaves(c.scrutinee(), leaf, visit); c.branches().forEach(b -> walkUserLeaves(b, leaf, visit)); }
        }
    }

    // --- compilation and evaluation ---

    static CompileResult compile(String source, String method, OptimizationLevel level, boolean maps, String... disabledRules) {
        var options = new CompilerOptions()
                .setOptimizationLevel(level).setSourceMapEnabled(maps).setOptimizationCostProfile(PROFILE);
        for (var rule : disabledRules) options.disableOptimizationRule(rule);
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(source, method);
    }

    /** The validator entry point with its PIR captured (the plain {@code compile} drops it). */
    private static CompileResult compileValidator(String source, boolean projections) {
        var options = new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE);
        if (!projections) options.disableOptimizationRule(ValueConversionSharingPass.PROJECTION_RULE);
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileContractWithDetails(source).compileResult();
    }

    private static PlutusData context(PlutusData txInfo, PlutusData redeemer) {
        return PlutusData.constr(0, txInfo, redeemer, PlutusData.constr(0, PlutusData.bytes(new byte[28])));
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

    /**
     * The machine cost of one lambda, one application and one variable lookup under the pinned
     * PV11 profile, measured as {@code [(λv. v) 1]} minus {@code 1}: the most a shared binding
     * can add to a path that never reaches a later occurrence.
     */
    private static ExBudget bindingOverhead() {
        var vm = CompilerTestVm.pv11("Java");
        var one = Term.const_(Constant.integer(1));
        var bare = vm.evaluate(Program.plutusV3(one), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), null, EvalOptions.DEFAULT);
        var bound = vm.evaluate(Program.plutusV3(Term.apply(Term.lam("v", Term.var(1)), one)),
                CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), null, EvalOptions.DEFAULT);
        return new ExBudget(bound.budgetConsumed().cpuSteps() - bare.budgetConsumed().cpuSteps(),
                bound.budgetConsumed().memoryUnits() - bare.budgetConsumed().memoryUnits());
    }

    private static Program golden(int fixture, OptimizationLevel level, boolean maps) throws IOException {
        String id = fixture + "-" + level + "-" + maps;
        try (var input = O15ProjectionSharingTest.class.getResourceAsStream("/optimization/o15-pre-change-bytes.txt")) {
            assertNotNull(input);
            String hex = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(id + " ")).findFirst()
                    .orElseThrow(() -> new AssertionError("missing golden row " + id
                            + " in optimization/o15-pre-change-bytes.txt; recapture from the base commit"))
                    .substring(id.length() + 1);
            return UplcFlatDecoder.decodeProgram(HexFormat.of().parseHex(hex));
        }
    }
}
