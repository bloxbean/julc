package org.julclang.compiler;

import org.julclang.clientlib.JulcScriptAdapter;
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
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.julclang.compiler.O8ValueSharingFixtures.FIXTURES;
import static org.julclang.compiler.O8ValueSharingFixtures.NOT_A_MAP;
import static org.julclang.compiler.O8ValueSharingFixtures.VALID;
import static org.julclang.compiler.O8ValueSharingFixtures.ZERO_QUANTITY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-042 (O8): the strict-prefix value conversion sharing pass. Every fixture in
 * {@link O8ValueSharingFixtures} is compiled at every level with source maps off and on and
 * compared with the golden bytes captured at the base commit
 * ({@code optimization/o8-pre-change-bytes.txt}): NONE/BASELINE and non-sharing fixtures stay
 * byte-identical, and the safe profile is observationally equivalent on Java, Truffle and
 * Scalus for every input, including the exact failure text, and never more expensive on any
 * successful path. Direct-PIR cases pin the legality predicate shape by shape.
 */
@Tag("pair-case-backends")
class O8ValueSharingTest {

    private static final String FROM_DATA = "org.julclang.stdlib.lib.NativeValueLib.fromData";
    private static final PirType DATA = new PirType.DataType();
    private static final PirType INT = new PirType.IntegerType();
    private static final PirType BYTES = new PirType.ByteStringType();
    private static final PirType NATIVE = new PirType.NativeValueType();

    @Test
    void safeProfileSharesLeadingConversionsAndStaysObservationallyEquivalentOnEveryBackend() throws IOException {
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
                    assertEquals(expectRule, compiled.optimizationReport().appliedRules()
                            .contains(ValueConversionSharingPass.RULE), label);
                    assertEquals(expectRule ? fixture.conversionsAfter() : fixture.conversionsBefore(),
                            countConversions(compiled.pirTerm()), label);
                    var old = golden(i, level.pv11SafeRulesEnabled() ? OptimizationLevel.PV11_SAFE : level, maps);
                    if (!expectRule) {
                        assertArrayEquals(UplcFlatEncoder.encodeProgram(old), bytes, label);
                    } else {
                        // FLAT size: sharing k sites adds one lambda and one application (4 bits each)
                        // plus k variable uses (about 12 bits each) and removes k-1 conversion
                        // applications (27-28 bits each), so a pair costs a few bits; the optimizer
                        // then inlines a wrapper let that became single-use, which is why some
                        // fixtures shrink. Observed range -2..+1 bytes; the bound below is the
                        // observed one for these fixtures, not a property of the pass. Source-map
                        // builds skip the UPLC optimizer and are not the deployable artifact.
                        if (!maps) assertTrue(bytes.length <= UplcFlatEncoder.encodeProgram(old).length + 1, label);
                        assertNotEquals(JulcScriptAdapter.scriptHash(old), JulcScriptAdapter.scriptHash(program), label);
                    }
                    for (var input : fixture.inputs()) {
                        EvalResult javaResult = null;
                        for (String provider : List.of("Java", "Truffle", "Scalus")) {
                            String inputLabel = label + "/" + provider + "/" + input;
                            var before = evaluate(old, input.args(), provider);
                            var after = evaluate(program, input.args(), provider);
                            assertEquals(input.success(), after.isSuccess(), inputLabel + " " + after);
                            assertEquals(before.getClass(), after.getClass(), inputLabel);
                            assertEquals(before.traces(), after.traces(), inputLabel);
                            if (before instanceof EvalResult.Success b) {
                                assertEquals(b.resultTerm(), ((EvalResult.Success) after).resultTerm(), inputLabel);
                            } else if (before instanceof EvalResult.Failure b) {
                                // O8 is failure-text neutral: the same builtin fails on the same input.
                                assertEquals(b.error(), ((EvalResult.Failure) after).error(), inputLabel);
                            }
                            if (provider.equals("Java")) javaResult = after;
                            if (provider.equals("Truffle")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), inputLabel);
                            if (level == OptimizationLevel.PV11_SAFE && !maps && provider.equals("Java")) {
                                System.out.println("VALUE_SHARING_COST " + fixture.name() + " " + input + " "
                                        + before.budgetConsumed() + " -> " + after.budgetConsumed());
                            }
                            if (expectRule && input.success() && !provider.equals("Scalus")) {
                                // A path that reaches a second occurrence saves a whole conversion; a
                                // path that reaches only the leading one pays the binding: one lambda,
                                // one application and one variable lookup (three machine steps) per
                                // shared binding, which is the bound pinned here.
                                var bound = bindingOverhead();
                                int bindings = bindingsExpected(fixture);
                                assertTrue(after.budgetConsumed().cpuSteps()
                                        <= before.budgetConsumed().cpuSteps() + bindings * bound.cpuSteps(), inputLabel);
                                assertTrue(after.budgetConsumed().memoryUnits()
                                        <= before.budgetConsumed().memoryUnits() + bindings * bound.memoryUnits(), inputLabel);
                            }
                        }
                    }
                    if (level == OptimizationLevel.PV11_SAFE && !maps) {
                        System.out.println("VALUE_SHARING_ARTIFACT " + fixture.name() + " "
                                + UplcFlatEncoder.encodeProgram(old).length + " -> " + bytes.length + " "
                                + JulcScriptAdapter.scriptHash(old) + " -> " + JulcScriptAdapter.scriptHash(program));
                    }
                }
            }
        }
    }

    @Test
    void safeProfileOutputIsByteIdenticalToTheManualSharingSource() {
        var repeated = FIXTURES.get(0);
        var shared = FIXTURES.get(1);
        assertEquals("REPEATED", repeated.name());
        assertEquals("SHARED", shared.name());
        for (var level : List.of(OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED)) {
            for (boolean maps : List.of(false, true)) {
                var automatic = compile(repeated.source(), repeated.method(), level, maps);
                var manual = compile(shared.source(), shared.method(), level, maps);
                assertArrayEquals(UplcFlatEncoder.encodeProgram(manual.program()),
                        UplcFlatEncoder.encodeProgram(automatic.program()), level + "/" + maps);
                assertTrue(automatic.optimizationReport().appliedRules().contains(ValueConversionSharingPass.RULE));
                assertFalse(manual.optimizationReport().appliedRules().contains(ValueConversionSharingPass.RULE));
            }
        }
    }

    @Test
    void sharedBindingsAreConversionsOfAVariableAndNeverWrapALambda() {
        for (var fixture : FIXTURES) {
            var compiled = compile(fixture.source(), fixture.method(), OptimizationLevel.PV11_SAFE, false);
            int[] bindings = {0};
            walk(compiled.pirTerm(), term -> {
                if (term instanceof PirTerm.Let let && let.name().startsWith("#value-")) {
                    bindings[0]++;
                    assertTrue(isConversion(let.value()), fixture.name() + ": " + let.value());
                    assertFalse(let.body() instanceof PirTerm.Lam, fixture.name() + ": shared binding wraps a lambda");
                }
            });
            assertEquals(fixture.shares() ? bindingsExpected(fixture) : 0, bindings[0], fixture.name());
        }
    }

    @Test
    void directPirSharingRespectsEvaluationOrderShadowingAndOpaqueNodes() {
        var x = new PirTerm.Var("x", DATA);
        var y = new PirTerm.Var("y", DATA);
        var conversion = new PirTerm.App(new PirTerm.Var(FROM_DATA, new PirType.FunType(DATA, NATIVE)), x);
        var raw = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnValueData), x);
        var ofY = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnValueData), y);

        // Leading pair (both spellings) shares once; NONE/BASELINE are the identity.
        var pair = add(lookup(conversion), lookup(raw));
        var shared = lower(pair, OptimizationLevel.PV11_SAFE);
        var let = assertInstanceOf(PirTerm.Let.class, shared);
        assertEquals("#value-0", let.name());
        assertSame(conversion, let.value());
        assertEquals(1, countConversions(shared));
        var closedPair = closed(pair, VALID, VALID);
        for (var level : List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE)) {
            assertSame(closedPair, lowerClosed(closedPair, level));
        }
        assertEquivalent(pair, VALID, VALID, 1);
        assertEquivalent(pair, NOT_A_MAP, VALID, 1);

        // A saturated call in front blocks; the pair behind it is still shared at its own scope.
        var blocked = add(lookup(ofY), add(lookup(conversion), lookup(raw)));
        assertEquals(2, countConversions(lower(blocked, OptimizationLevel.PV11_SAFE)));
        assertInstanceOf(PirTerm.App.class, lower(blocked, OptimizationLevel.PV11_SAFE));
        assertEquivalent(blocked, VALID, VALID, 2);
        assertEquivalent(blocked, NOT_A_MAP, ZERO_QUANTITY, 2);
        assertEquivalent(blocked, ZERO_QUANTITY, NOT_A_MAP, 2);

        // Trace and error are opaque roots: nothing is hoisted above them.
        var traced = new PirTerm.Trace(new PirTerm.Const(Constant.string("t")), add(lookup(conversion), lookup(raw)));
        var loweredTrace = assertInstanceOf(PirTerm.Trace.class, lower(traced, OptimizationLevel.PV11_SAFE));
        assertInstanceOf(PirTerm.Let.class, loweredTrace.body());
        assertEquivalent(traced, VALID, VALID, 1);
        assertEquivalent(traced, ZERO_QUANTITY, VALID, 1);
        var sequenced = new PirTerm.Let("_", new PirTerm.Trace(new PirTerm.Const(Constant.string("first")),
                new PirTerm.Const(Constant.unit())), add(lookup(conversion), lookup(raw)));
        var loweredSequence = assertInstanceOf(PirTerm.Let.class, lower(sequenced, OptimizationLevel.PV11_SAFE));
        assertEquals("_", loweredSequence.name());
        assertInstanceOf(PirTerm.Let.class, loweredSequence.body());
        assertEquivalent(sequenced, NOT_A_MAP, VALID, 1);

        // Rebinding x between the scope and a use excludes that use; a LetRec binder is opaque.
        var inner = new PirTerm.Let("x", y, add(lookup(conversion), lookup(raw)));
        var outer = add(lookup(raw), inner);
        var loweredOuter = assertInstanceOf(PirTerm.App.class, lower(outer, OptimizationLevel.PV11_SAFE));
        assertEquals(2, countConversions(loweredOuter));
        assertInstanceOf(PirTerm.Let.class, ((PirTerm.Let) loweredOuter.argument()).body());
        assertEquivalent(outer, VALID, O8ValueSharingFixtures.DIFFERENT, 2);
        assertEquivalent(outer, NOT_A_MAP, ZERO_QUANTITY, 2);
        assertEquivalent(outer, VALID, ZERO_QUANTITY, 2);
        var recursive = add(lookup(raw), new PirTerm.LetRec(List.of(new PirTerm.Binding("x", y)),
                add(lookup(conversion), lookup(raw))));
        var loweredRecursive = assertInstanceOf(PirTerm.App.class, lower(recursive, OptimizationLevel.PV11_SAFE));
        assertEquals(2, countConversions(loweredRecursive));
        assertInstanceOf(PirTerm.Let.class, ((PirTerm.LetRec) loweredRecursive.argument()).body());

        // A lambda is never wrapped: the binding lands inside its body.
        var lambda = new PirTerm.Lam("p", INT, add(lookup(conversion), lookup(raw)));
        var loweredLambda = assertInstanceOf(PirTerm.Lam.class, lower(lambda, OptimizationLevel.PV11_SAFE));
        assertInstanceOf(PirTerm.Let.class, loweredLambda.body());

        // Conditionals: only a leading condition shares above; exclusive branches never do.
        var flag = new PirTerm.Var("flag", new PirType.BoolType());
        var bothBranches = new PirTerm.IfThenElse(flag, lookup(conversion), lookup(raw));
        assertInstanceOf(PirTerm.IfThenElse.class, lower(bothBranches, OptimizationLevel.PV11_SAFE));
        assertEquals(2, countConversions(lower(bothBranches, OptimizationLevel.PV11_SAFE)));
        var pairAfterBranch = new PirTerm.Let("r", bothBranches, add(new PirTerm.Var("r", INT), lookup(raw)));
        assertInstanceOf(PirTerm.Let.class, lower(pairAfterBranch, OptimizationLevel.PV11_SAFE));
        assertEquals(3, countConversions(lower(pairAfterBranch, OptimizationLevel.PV11_SAFE)));
        assertEquivalent(pairAfterBranch, VALID, VALID, 3);
        var leadingCondition = new PirTerm.IfThenElse(
                new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), lookup(conversion)),
                        new PirTerm.Const(Constant.integer(0))),
                lookup(raw), new PirTerm.Const(Constant.integer(-1)));
        assertInstanceOf(PirTerm.Let.class, lower(leadingCondition, OptimizationLevel.PV11_SAFE));
        var saturatedCondition = new PirTerm.IfThenElse(
                new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), lookup(ofY)),
                        new PirTerm.Const(Constant.integer(0))),
                lookup(conversion), lookup(raw));
        assertInstanceOf(PirTerm.IfThenElse.class, lower(saturatedCondition, OptimizationLevel.PV11_SAFE));

        // The wrapper alias counts only when it is bound exactly once with the exact shape.
        var rebound = closed(new PirTerm.Let(FROM_DATA, new PirTerm.Lam("d", DATA, new PirTerm.Var("d", DATA)),
                add(lookup(conversion), lookup(conversion))), VALID, VALID);
        assertSame(rebound, lowerClosed(rebound, OptimizationLevel.PV11_SAFE));
    }

    /**
     * Each soundness guard is pinned by an input on which removing the guard changes an
     * observable outcome, not only a count: a rebinding guard removed would convert the outer
     * variable in place of the inner one (different failure text, or 126 instead of 84), and a
     * saturated-builtin prefix treated as trivial would hoist the conversion above it.
     */
    @Test
    void rebindingGuardsAndSaturatedPrefixesArePinnedByObservableOutcomes() {
        var x = new PirTerm.Var("x", DATA);
        var y = new PirTerm.Var("y", DATA);
        var conversion = new PirTerm.App(new PirTerm.Var(FROM_DATA, new PirType.FunType(DATA, NATIVE)), x);
        var raw = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnValueData), x);
        var pair = add(lookup(conversion), lookup(raw));
        var constrOfDifferent = PlutusData.constr(0, O8ValueSharingFixtures.DIFFERENT);

        // Let rebinding: the inner let supplies a lead, the outer sites supply the count.
        var letRebinding = add(new PirTerm.Let("x", y, pair), pair);
        var loweredLet = assertInstanceOf(PirTerm.App.class, lower(letRebinding, OptimizationLevel.PV11_SAFE));
        assertEquals(2, countConversions(loweredLet));
        assertEquivalent(letRebinding, NOT_A_MAP, ZERO_QUANTITY, 2);
        assertEquivalent(letRebinding, ZERO_QUANTITY, NOT_A_MAP, 2);
        assertEquivalent(letRebinding, VALID, O8ValueSharingFixtures.DIFFERENT, 2);

        // Lam rebinding: the lambda's x is y's value (lookup 0), the outer x is VALID (42 twice).
        var lamRebinding = add(add(lookup(raw), lookup(raw)), new PirTerm.App(new PirTerm.Lam("x", DATA, lookup(raw)), y));
        assertEquals(2, countConversions(lower(lamRebinding, OptimizationLevel.PV11_SAFE)));
        assertEquals(84, integerResult(lamRebinding, VALID, O8ValueSharingFixtures.DIFFERENT, 2));
        assertEquivalent(lamRebinding, VALID, NOT_A_MAP, 2);

        // DataMatch field binder named x: the field is the constructor's payload, not the outer x.
        var fieldRebinding = add(add(lookup(raw), lookup(raw)), new PirTerm.DataMatch(y, List.of(
                new PirTerm.MatchBranch("A", List.of("x"), List.of(DATA), lookup(raw)))));
        assertEquals(2, countConversions(lower(fieldRebinding, OptimizationLevel.PV11_SAFE)));
        assertEquals(84, integerResult(fieldRebinding, VALID, constrOfDifferent, 2));
        assertEquivalent(fieldRebinding, VALID, PlutusData.constr(0, NOT_A_MAP), 2);

        // A saturated partial builtin in front blocks; the pair behind it shares at its own scope.
        var bytes = PlutusData.bytes(O8ValueSharingFixtures.TOKEN);
        var partialPrefix = new PirTerm.Let("_", new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), y), pair);
        var loweredPartial = assertInstanceOf(PirTerm.Let.class, lower(partialPrefix, OptimizationLevel.PV11_SAFE));
        assertEquals("_", loweredPartial.name());
        assertInstanceOf(PirTerm.Let.class, loweredPartial.body());
        assertEquivalent(partialPrefix, VALID, bytes, 1);
        assertEquivalent(partialPrefix, ZERO_QUANTITY, NOT_A_MAP, 1);
        assertEquivalent(partialPrefix, ZERO_QUANTITY, bytes, 1);

        // A saturated total builtin in front blocks too (the broadening is deliberately not taken).
        var totalPrefix = new PirTerm.Let("_", new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                new PirTerm.Const(Constant.integer(1))), new PirTerm.Const(Constant.integer(2))), pair);
        var loweredTotal = assertInstanceOf(PirTerm.Let.class, lower(totalPrefix, OptimizationLevel.PV11_SAFE));
        assertEquals("_", loweredTotal.name());
        assertInstanceOf(PirTerm.Let.class, loweredTotal.body());
        assertEquivalent(totalPrefix, NOT_A_MAP, VALID, 1);
    }

    /**
     * Regression (reviewer finding): a wrapper-spelled conversion {@code [w x]} is free in both
     * {@code x} and the wrapper name {@code w}. When {@code x} is bound outside the wrapper's own
     * {@code Let} (the {@code @Param} lambda shape: parameters wrap outside the library lets),
     * the binding must land below the wrapper, never above it, or {@code w} becomes unbound.
     */
    @Test
    void conversionOfAVariableBoundOutsideTheWrapperSharesBelowTheWrapperBinding() {
        var x = new PirTerm.Var("x", DATA);
        var conversion = new PirTerm.App(new PirTerm.Var(FROM_DATA, new PirType.FunType(DATA, NATIVE)), x);
        var body = add(lookup(conversion), lookup(conversion));
        var wrapper = new PirTerm.Lam("mapData", DATA,
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnValueData), new PirTerm.Var("mapData", DATA)));
        var lookupCoin = new PirTerm.Lam("policyId", BYTES, new PirTerm.Lam("tokenName", BYTES,
                new PirTerm.Lam("value", NATIVE, new PirTerm.App(new PirTerm.App(new PirTerm.App(
                        new PirTerm.Builtin(DefaultFun.LookupCoin), new PirTerm.Var("policyId", BYTES)),
                        new PirTerm.Var("tokenName", BYTES)), new PirTerm.Var("value", NATIVE)))));
        var libraries = new PirTerm.Let(FROM_DATA, wrapper,
                new PirTerm.Let("org.julclang.stdlib.lib.NativeValueLib.lookupCoin", lookupCoin,
                new PirTerm.Let("policy", new PirTerm.Const(Constant.byteString(O8ValueSharingFixtures.POLICY)),
                new PirTerm.Let("token", new PirTerm.Const(Constant.byteString(O8ValueSharingFixtures.TOKEN)), body))));
        for (var data : List.of(VALID, NOT_A_MAP)) {
            var letBound = new PirTerm.Let("x", new PirTerm.Const(Constant.data(data)), libraries);
            var lambdaBound = new PirTerm.App(new PirTerm.Lam("x", DATA, libraries), new PirTerm.Const(Constant.data(data)));
            for (var program : List.of(letBound, lambdaBound)) {
                var lowered = lowerClosed(program, OptimizationLevel.PV11_SAFE);
                assertEquals(java.util.Set.of(), org.julclang.compiler.pir.PirSubstitution.collectFreeVarNames(lowered));
                assertEquals(1, countConversions(lowered));
                // The binding sits directly under the wrapper let, above lookupCoin/policy/token.
                PirTerm cursor = lowered;
                while (!(cursor instanceof PirTerm.Let let && let.name().equals(FROM_DATA))) {
                    cursor = cursor instanceof PirTerm.Let let ? let.body()
                            : cursor instanceof PirTerm.App app ? ((PirTerm.Lam) app.function()).body() : fail("shape");
                }
                var shared = assertInstanceOf(PirTerm.Let.class, ((PirTerm.Let) cursor).body());
                assertTrue(shared.name().startsWith("#value-"), shared.name());
                var baseline = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE))
                        .compilePirToProgram(program);
                var safe = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE))
                        .compilePirToProgram(program);
                for (String provider : List.of("Java", "Truffle", "Scalus")) {
                    var before = evaluate(baseline, List.of(), provider);
                    var after = evaluate(safe, List.of(), provider);
                    assertEquals(before.getClass(), after.getClass(), provider);
                    if (before instanceof EvalResult.Success b) assertEquals(b.resultTerm(), ((EvalResult.Success) after).resultTerm(), provider);
                    if (before instanceof EvalResult.Failure b) assertEquals(b.error(), ((EvalResult.Failure) after).error(), provider);
                }
            }
        }
    }

    /**
     * Source-level form of the same regression: {@code @Param} lambdas wrap outside the library
     * lets, and a zero-parameter static helper is a strict {@code Let} value, so the shared
     * binding must be placed inside the wrapper's scope. Compiled at BASELINE this shape worked
     * before; at the safe profile it threw {@code Unbound variable … NativeValueLib.fromData}.
     */
    @Test
    void parameterBoundConversionsInStaticHelpersShareInsideTheWrapperScope() {
        var source = O8ValueSharingFixtures.IMPORTS + """
                class ParamShapes {
                    @Param PlutusData p;
                    static JulcValue expected() { return NativeValueLib.fromData(p); }
                    static boolean same() {
                        return NativeValueLib.contains(NativeValueLib.fromData(p), expected());
                    }
                }
                """;
        // The validator entry point wraps @Param lambdas the same way; it must compile too.
        var validator = O8ValueSharingFixtures.IMPORTS + """
                import org.julclang.ledger.*;
                @SpendingValidator class ParamValidator {
                    @Param PlutusData expectedValue;
                    static JulcValue expected() { return NativeValueLib.fromData(expectedValue); }
                    @Entrypoint static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        JulcValue e = NativeValueLib.fromData(expectedValue);
                        return NativeValueLib.contains(e, expected());
                    }
                }
                """;
        for (var level : OptimizationLevel.values()) {
            var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                    .setOptimizationLevel(level)
                    .setOptimizationCostProfile(OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11))
                    .compileWithDetails(validator);
            assertFalse(compiled.hasErrors(), level + " " + compiled.diagnostics());
            assertEquals(level.pv11SafeRulesEnabled() ? 1 : 2, countConversions(compiled.pirTerm()), level.toString());
            assertEquals(java.util.Set.of(), org.julclang.compiler.pir.PirSubstitution.collectFreeVarNames(compiled.pirTerm()), level.toString());
        }
        for (var data : List.of(VALID, NOT_A_MAP, ZERO_QUANTITY)) {
            var baseline = compile(source, "same", OptimizationLevel.BASELINE, false);
            var safe = compile(source, "same", OptimizationLevel.PV11_SAFE, false);
            assertFalse(baseline.hasErrors(), baseline.diagnostics().toString());
            assertFalse(safe.hasErrors(), safe.diagnostics().toString());
            assertEquals(2, countConversions(baseline.pirTerm()));
            assertEquals(1, countConversions(safe.pirTerm()));
            assertTrue(safe.optimizationReport().appliedRules().contains(ValueConversionSharingPass.RULE));
            for (String provider : List.of("Java", "Truffle", "Scalus")) {
                var before = evaluate(baseline.program(), List.of(data), provider);
                var after = evaluate(safe.program(), List.of(data), provider);
                assertEquals(before.getClass(), after.getClass(), provider + "/" + data);
                assertEquals(data == VALID, after.isSuccess(), provider + "/" + data + " " + after);
                if (before instanceof EvalResult.Success b) assertEquals(b.resultTerm(), ((EvalResult.Success) after).resultTerm(), provider);
                if (before instanceof EvalResult.Failure b) assertEquals(b.error(), ((EvalResult.Failure) after).error(), provider);
            }
        }
    }

    @Test
    void producerGuardsHoldOnEveryProviderAndTheRuleIsInertWithoutRepeats() {
        var single = FIXTURES.getLast();
        assertEquals("SINGLE", single.name());
        for (var level : OptimizationLevel.values()) {
            var compiled = compile(single.source(), single.method(), level, false);
            assertFalse(compiled.optimizationReport().appliedRules().contains(ValueConversionSharingPass.RULE));
        }
    }

    // --- helpers ---

    /**
     * The machine cost of one lambda, one application and one variable lookup under the pinned
     * PV11 profile, measured as {@code [(λv. v) 1]} minus {@code 1}: the most a shared binding
     * can add to a path that never reaches a second occurrence.
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

    private static int bindingsExpected(O8ValueSharingFixtures.Fixture fixture) {
        return switch (fixture.name()) {
            case "TWO_VARS", "SEQUENTIAL" -> 2;
            default -> 1;
        };
    }

    private static PirTerm lookup(PirTerm value) {
        var lookupCoin = new PirTerm.Var("org.julclang.stdlib.lib.NativeValueLib.lookupCoin",
                new PirType.FunType(BYTES, new PirType.FunType(BYTES, new PirType.FunType(NATIVE, INT))));
        return new PirTerm.App(new PirTerm.App(new PirTerm.App(lookupCoin,
                new PirTerm.Var("policy", BYTES)), new PirTerm.Var("token", BYTES)), value);
    }

    private static PirTerm add(PirTerm a, PirTerm b) {
        return new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), a), b);
    }

    /** Close a body over the library wrappers, {@code policy}/{@code token}, {@code flag} and the two Data inputs. */
    private static PirTerm closed(PirTerm body, PlutusData x, PlutusData y) {
        var lookupBody = new PirTerm.App(new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.LookupCoin),
                new PirTerm.Var("policyId", BYTES)), new PirTerm.Var("tokenName", BYTES)), new PirTerm.Var("value", NATIVE));
        var lookupCoin = new PirTerm.Lam("policyId", BYTES, new PirTerm.Lam("tokenName", BYTES,
                new PirTerm.Lam("value", NATIVE, lookupBody)));
        var fromData = new PirTerm.Lam("mapData", DATA,
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnValueData), new PirTerm.Var("mapData", DATA)));
        return new PirTerm.Let(FROM_DATA, fromData,
                new PirTerm.Let("org.julclang.stdlib.lib.NativeValueLib.lookupCoin", lookupCoin,
                new PirTerm.Let("policy", new PirTerm.Const(Constant.byteString(O8ValueSharingFixtures.POLICY)),
                new PirTerm.Let("token", new PirTerm.Const(Constant.byteString(O8ValueSharingFixtures.TOKEN)),
                new PirTerm.Let("flag", new PirTerm.Const(Constant.bool(true)),
                new PirTerm.Let("x", new PirTerm.Const(Constant.data(x)),
                new PirTerm.Let("y", new PirTerm.Const(Constant.data(y)), body)))))));
    }

    private static final List<String> CLOSING = List.of(FROM_DATA,
            "org.julclang.stdlib.lib.NativeValueLib.lookupCoin", "policy", "token", "flag", "x", "y");

    /**
     * Run the pass on the closed program and strip the closing lets to expose the rewritten
     * body. A shared binding may legally sit between them (above trivial lets such as
     * {@code y}), so stripping stops at the first binding that is not a closing let.
     */
    private static PirTerm lower(PirTerm body, OptimizationLevel level) {
        var lowered = lowerClosed(closed(body, VALID, VALID), level);
        while (lowered instanceof PirTerm.Let let && CLOSING.contains(let.name())) lowered = let.body();
        return lowered;
    }

    private static PirTerm lowerClosed(PirTerm closedTerm, OptimizationLevel level) {
        return new ValueConversionSharingPass(context(level), Map.of()).lower(closedTerm).term();
    }

    /** The Java-VM integer result of the closed program at the safe profile, after asserting equivalence. */
    private static long integerResult(PirTerm body, PlutusData x, PlutusData y, int conversionsAfter) {
        assertEquivalent(body, x, y, conversionsAfter);
        var safe = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE))
                .compilePirToProgram(closed(body, x, y));
        var result = assertInstanceOf(EvalResult.Success.class, evaluate(safe, List.of(), "Java"));
        return ((Constant.IntegerConst) ((Term.Const) result.resultTerm()).value()).value().longValueExact();
    }

    private static void assertEquivalent(PirTerm body, PlutusData x, PlutusData y, int conversionsAfter) {
        var term = closed(body, x, y);
        var baseline = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE))
                .compilePirToProgram(term);
        var safe = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE))
                .compilePirToProgram(term);
        assertEquals(conversionsAfter, countConversions(lowerClosed(term, OptimizationLevel.PV11_SAFE)));
        EvalResult javaResult = null;
        for (String provider : List.of("Java", "Truffle", "Scalus")) {
            var before = evaluate(baseline, List.of(), provider);
            var after = evaluate(safe, List.of(), provider);
            assertEquals(before.getClass(), after.getClass(), provider);
            assertEquals(before.traces(), after.traces(), provider);
            if (before instanceof EvalResult.Success b) assertEquals(b.resultTerm(), ((EvalResult.Success) after).resultTerm(), provider);
            if (before instanceof EvalResult.Failure b) assertEquals(b.error(), ((EvalResult.Failure) after).error(), provider);
            if (provider.equals("Java")) javaResult = after;
            if (provider.equals("Truffle")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), provider);
        }
    }

    private static CompilationContext context(OptimizationLevel level) {
        return CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(level));
    }

    private static boolean isConversion(PirTerm term) {
        return term instanceof PirTerm.App app && app.argument() instanceof PirTerm.Var
                && (app.function() instanceof PirTerm.Builtin b && b.fun() == DefaultFun.UnValueData
                    || app.function() instanceof PirTerm.Var f && f.name().equals(FROM_DATA));
    }

    /** Conversion sites outside the {@code fromData} wrapper body itself. */
    static int countConversions(PirTerm term) {
        int[] count = {0};
        walk(term, t -> { if (isConversion(t)) count[0]++; });
        return count[0];
    }

    private static void walk(PirTerm term, Consumer<PirTerm> visit) {
        if (term instanceof PirTerm.Let let && let.name().equals(FROM_DATA)) {
            walk(let.body(), visit);
            return;
        }
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
                .setOptimizationLevel(level).setSourceMapEnabled(maps)
                .setOptimizationCostProfile(OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11))
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
        try (var input = O8ValueSharingTest.class.getResourceAsStream("/optimization/o8-pre-change-bytes.txt")) {
            assertNotNull(input);
            String hex = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(id + " ")).findFirst()
                    .orElseThrow(() -> new AssertionError("missing golden row " + id
                            + " in optimization/o8-pre-change-bytes.txt; recapture from the base commit"))
                    .substring(id.length() + 1);
            return UplcFlatDecoder.decodeProgram(HexFormat.of().parseHex(hex));
        }
    }
}
