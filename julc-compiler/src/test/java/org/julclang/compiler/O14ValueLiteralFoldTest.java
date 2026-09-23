package org.julclang.compiler;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.ValueLiteralFoldPass;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.NativeValueSemantics;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.FlatWriter;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.source.SourceLocation;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.OptimizationCostProfile;
import org.julclang.vm.OptimizationCostProfiles;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.UplcVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.julclang.compiler.O14ValueLiteralFixtures.FIXTURES;
import static org.julclang.compiler.O14ValueLiteralFixtures.MAX_QUANTITY;
import static org.julclang.compiler.O14ValueLiteralFixtures.P32_BYTES;
import static org.julclang.compiler.O14ValueLiteralFixtures.valueData;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-045 (O14): literal folding of the PV11 native Value builtins. Every fixture in
 * {@link O14ValueLiteralFixtures} is compiled at every level with the rule switched off and
 * on: NONE and BASELINE never fold and stay byte-identical either way; at the safe and costed
 * profiles the folded program is observationally equivalent on Java, Truffle and Scalus for
 * every input (results, traces, failure text), never larger, and strictly smaller when a fold
 * fired. Direct-PIR cases pin the strict {@code UnValueData} decoding of Data literals, the
 * wrapper and literal-local recognition, the size objective and source positions.
 */
@Tag("pair-case-backends")
class O14ValueLiteralFoldTest {

    private static final OptimizationCostProfile PROFILE = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
    private static final List<String> PROVIDERS = List.of("Java", "Truffle", "Scalus");
    private static final String LIB = "org.julclang.stdlib.lib.NativeValueLib.";
    private static final Set<DefaultFun> VALUE_BUILTINS = Set.of(
            DefaultFun.InsertCoin, DefaultFun.LookupCoin, DefaultFun.UnionValue, DefaultFun.ValueContains,
            DefaultFun.ScaleValue, DefaultFun.ValueData, DefaultFun.UnValueData);
    private static final Set<String> WRAPPERS = Set.of("insertCoin", "lookupCoin", "union", "contains", "scale",
            "fromData", "toData");
    private static final PirType DATA = new PirType.DataType();
    private static final byte[] P = {1, 2, 3};
    private static final byte[] T = {9};

    /** The value every successful fixture path must compute (folded or not). */
    private static final Map<String, Constant> EXPECTED = Map.ofEntries(
            Map.entry("EMPTY_LOOKUP/key", Constant.integer(0)),
            Map.entry("SINGLETON_LOOKUP/run", Constant.integer(100)),
            Map.entry("LOVELACE_UNION/run", Constant.integer(12)),
            Map.entry("INSERT_CHAIN/run", Constant.data(PlutusData.map(
                    new PlutusData.Pair(PlutusData.bytes(new byte[]{1}), PlutusData.map(new PlutusData.Pair(PlutusData.bytes(new byte[]{1}), PlutusData.integer(3)))),
                    new PlutusData.Pair(PlutusData.bytes(new byte[]{2}), PlutusData.map(new PlutusData.Pair(PlutusData.bytes(new byte[]{2}), PlutusData.integer(2))))))),
            Map.entry("UNION_CANCEL/run", Constant.data(PlutusData.map())),
            Map.entry("SCALE/run", Constant.integer(12)),
            Map.entry("SCALE_ZERO/run", Constant.data(PlutusData.map())),
            Map.entry("CONTAINS_TRUE/run", Constant.bool(true)),
            Map.entry("CONTAINS_FALSE/run", Constant.bool(false)),
            Map.entry("ROUND_TRIP/run", Constant.integer(9)),
            Map.entry("RUNTIME_QUANTITY/four", Constant.integer(4)),
            Map.entry("RUNTIME_QUANTITY/zero", Constant.integer(0)),
            Map.entry("RUNTIME_VALUE/holds", Constant.bool(true)),
            Map.entry("RUNTIME_VALUE/empty", Constant.bool(false)),
            Map.entry("LOCAL_LITERAL/run", Constant.integer(2)),
            Map.entry("ALIAS_LOCAL/run", Constant.integer(1)),
            Map.entry("KEY_MAX/run", Constant.integer(1)),
            Map.entry("LONG_KEY_ZERO/run", Constant.data(PlutusData.map())),
            Map.entry("QUANTITY_MAX/run", Constant.integer(MAX_QUANTITY)),
            Map.entry("QUANTITY_MIN/run", Constant.integer(MAX_QUANTITY.add(BigInteger.ONE).negate())),
            Map.entry("TRACE_AROUND/run", Constant.integer(1)),
            Map.entry("ERROR_ARM/pass", Constant.integer(1)),
            Map.entry("MIXED_KEY/present", Constant.integer(1)),
            Map.entry("MIXED_KEY/absent", Constant.integer(0)),
            Map.entry("UNUSED_PARAM/integer", Constant.integer(1)),
            Map.entry("USER_WRAPPER/run", Constant.integer(3)),
            Map.entry("SHARED_LITERAL/one", Constant.data(valueData(P32_BYTES, T, 6))),
            Map.entry("SHARED_LITERAL/zero", Constant.data(valueData(P32_BYTES, T, 5))),
            Map.entry("SHARED_LITERAL/cancel", Constant.data(PlutusData.map())),
            Map.entry("SHARED_LITERAL_ONLY/run", Constant.data(valueData(P32_BYTES, T, 5))),
            Map.entry("SHARED_ALIAS/one", Constant.data(valueData(P32_BYTES, T, 6))),
            Map.entry("SHARED_ALIAS/zero", Constant.data(valueData(P32_BYTES, T, 5))),
            Map.entry("SHARED_ALIAS/cancel", Constant.data(PlutusData.map())));

    @Test
    void safeProfileFoldsLiteralCallsAndStaysObservationallyEquivalentOnEveryBackend() {
        for (var fixture : FIXTURES) {
            for (var level : OptimizationLevel.values()) {
                String label = fixture.name() + "/" + level;
                var off = compile(fixture, level, false, ValueLiteralFoldPass.RULE);
                var on = compile(fixture, level, false);
                assertFalse(off.hasErrors(), label + " " + off.diagnostics());
                assertFalse(on.hasErrors(), label + " " + on.diagnostics());
                var offBytes = UplcFlatEncoder.encodeProgram(off.program());
                var onBytes = UplcFlatEncoder.encodeProgram(on.program());
                assertArrayEquals(onBytes, UplcFlatEncoder.encodeProgram(compile(fixture, level, false).program()), label + " determinism");
                assertFalse(off.optimizationReport().appliedRules().contains(ValueLiteralFoldPass.RULE), label);
                boolean expectRule = level.pv11SafeRulesEnabled() && fixture.folds();
                assertEquals(expectRule, on.optimizationReport().appliedRules().contains(ValueLiteralFoldPass.RULE), label);
                assertEquals(fixture.callsBefore(), countCalls(off.pirTerm()), label + " calls with the rule off");
                assertEquals(level.pv11SafeRulesEnabled() ? fixture.callsAfter() : fixture.callsBefore(),
                        countCalls(on.pirTerm()), label + " calls with the rule on");
                if (!level.pv11SafeRulesEnabled()) {
                    assertArrayEquals(offBytes, onBytes, label + " NONE/BASELINE are not touched");
                } else if (expectRule) {
                    assertTrue(onBytes.length < offBytes.length, label + " " + offBytes.length + " -> " + onBytes.length);
                    assertNotEquals(JulcScriptAdapter.scriptHash(off.program()), JulcScriptAdapter.scriptHash(on.program()), label);
                } else {
                    assertArrayEquals(offBytes, onBytes, label + " nothing to fold");
                }
                // Source maps only add positions: the same folds fire.
                var mapped = compile(fixture, level, true);
                assertEquals(expectRule, mapped.optimizationReport().appliedRules().contains(ValueLiteralFoldPass.RULE), label + " maps");
                for (var input : fixture.inputs()) {
                    EvalResult javaResult = null;
                    for (String provider : PROVIDERS) {
                        String inputLabel = label + "/" + provider + "/" + input;
                        var before = evaluate(off.program(), input.args(), provider);
                        var after = evaluate(on.program(), input.args(), provider);
                        assertEquals(input.success(), before.isSuccess(), inputLabel + " " + before);
                        assertEquals(input.success(), after.isSuccess(), inputLabel + " " + after);
                        assertEquals(before.traces(), after.traces(), inputLabel);
                        if (before instanceof EvalResult.Success b) {
                            var a = assertInstanceOf(EvalResult.Success.class, after, inputLabel);
                            assertEquals(b.resultTerm(), a.resultTerm(), inputLabel);
                            var expected = EXPECTED.get(fixture.name() + "/" + input.name());
                            assertNotNull(expected, inputLabel + " has no expected value");
                            assertEquals(Term.const_(expected), a.resultTerm(), inputLabel);
                            // A literal costs one constant step; every fold removes at least one builtin call.
                            assertTrue(after.budgetConsumed().cpuSteps() <= before.budgetConsumed().cpuSteps(), inputLabel);
                            assertTrue(after.budgetConsumed().memoryUnits() <= before.budgetConsumed().memoryUnits(), inputLabel);
                        } else {
                            var b = (EvalResult.Failure) before;
                            var a = assertInstanceOf(EvalResult.Failure.class, after, inputLabel);
                            // A call the semantics reject stays as written: same builtin, same text.
                            assertEquals(b.error(), a.error(), inputLabel);
                        }
                        if (provider.equals("Java")) javaResult = after;
                        if (!provider.equals("Java")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed(), inputLabel);
                        if (level == OptimizationLevel.PV11_SAFE && provider.equals("Java")) {
                            System.out.println("VALUE_LITERAL_COST " + fixture.name() + " " + input + " "
                                    + before.budgetConsumed() + " -> " + after.budgetConsumed());
                        }
                    }
                }
                if (level == OptimizationLevel.PV11_SAFE) {
                    System.out.println("VALUE_LITERAL_ARTIFACT " + fixture.name() + " " + offBytes.length + " -> " + onBytes.length + " "
                            + JulcScriptAdapter.scriptHash(off.program()) + " -> " + JulcScriptAdapter.scriptHash(on.program()));
                }
            }
        }
    }

    /**
     * Strict decoding of Data literals: a canonical literal folds to exactly the Value the VM
     * decodes; every deviation the VM rejects is left as written and fails at runtime with the
     * same text on every backend.
     */
    @Test
    void dataLiteralsFoldOnlyWhenCanonical() {
        var canonical = mapOf(entry(bytes(1), mapOf(entry(bytes(1), PlutusData.integer(-7)))));
        var folded = assertInstanceOf(PirTerm.Const.class, lower(unValue(canonical), OptimizationLevel.PV11_SAFE));
        assertEquals(NativeValueSemantics.unValueData(canonical), folded.value());
        assertEquivalent(unValue(canonical));
        // A larger canonical literal decodes fine but is left as written by the size objective:
        // every byte string of a Value literal is byte-aligned in FLAT, the CBOR Data is not.
        var wide = mapOf(
                entry(bytes(1), mapOf(entry(bytes(1), PlutusData.integer(5)), entry(bytes(2), PlutusData.integer(-7)), entry(bytes(3), PlutusData.integer(9)))),
                entry(bytes(2), mapOf(entry(bytes(1), PlutusData.integer(1)), entry(bytes(2), PlutusData.integer(2)), entry(bytes(3), PlutusData.integer(3)))));
        var wideValue = NativeValueSemantics.unValueData(wide);
        assertTrue(bits(Term.const_(wideValue)) > bits(Term.apply(Term.builtin(DefaultFun.UnValueData), Term.const_(Constant.data(wide)))));
        var wideTerm = unValue(wide);
        assertSame(wideTerm, lower(wideTerm, OptimizationLevel.PV11_SAFE));
        assertEquivalent(wideTerm);

        var rejected = List.of(
                PlutusData.integer(1),
                mapOf(entry(PlutusData.integer(1), mapOf(entry(bytes(1), PlutusData.integer(1))))),
                mapOf(entry(PlutusData.bytes(new byte[33]), mapOf(entry(bytes(1), PlutusData.integer(1))))),
                mapOf(entry(bytes(2), mapOf(entry(bytes(1), PlutusData.integer(1)))), entry(bytes(1), mapOf(entry(bytes(1), PlutusData.integer(1))))),
                mapOf(entry(bytes(1), mapOf(entry(bytes(1), PlutusData.integer(1)))), entry(bytes(1), mapOf(entry(bytes(2), PlutusData.integer(1))))),
                mapOf(entry(bytes(1), PlutusData.integer(1))),
                mapOf(entry(bytes(1), mapOf())),
                mapOf(entry(bytes(1), mapOf(entry(PlutusData.integer(1), PlutusData.integer(1))))),
                mapOf(entry(bytes(1), mapOf(entry(PlutusData.bytes(new byte[33]), PlutusData.integer(1))))),
                mapOf(entry(bytes(1), mapOf(entry(bytes(2), PlutusData.integer(1)), entry(bytes(1), PlutusData.integer(1))))),
                mapOf(entry(bytes(1), mapOf(entry(bytes(1), PlutusData.integer(1)), entry(bytes(1), PlutusData.integer(2))))),
                mapOf(entry(bytes(1), mapOf(entry(bytes(1), PlutusData.integer(0))))),
                mapOf(entry(bytes(1), mapOf(entry(bytes(1), PlutusData.integer(MAX_QUANTITY.add(BigInteger.ONE)))))),
                mapOf(entry(bytes(1), mapOf(entry(bytes(1), PlutusData.bytes(new byte[]{1}))))));
        for (var data : rejected) {
            var term = unValue(data);
            assertSame(term, lower(term, OptimizationLevel.PV11_SAFE), data.toString());
            assertThrows(NativeValueSemantics.EvaluationFailure.class, () -> NativeValueSemantics.unValueData(data));
            assertEquivalent(term);
        }
    }

    /**
     * Recognition: bare builtins and once-bound wrappers fold; a trace or a runtime variable in
     * argument position (used by the wrapper or not), a name bound twice, and an unsaturated
     * wrapper never fold. The folded constant carries the call's source position. The size
     * objective is exact in bits and measures the call site.
     */
    @Test
    void wrappersLiteralLocalsPositionsAndObjective() {
        var single = value(entry(P, T, 5));
        var lookup = app(builtin(DefaultFun.LookupCoin), constant(Constant.byteString(P)), constant(Constant.byteString(T)), constant(single));
        assertEquals(new PirTerm.Const(Constant.integer(5)), lower(lookup, OptimizationLevel.PV11_SAFE));
        assertSame(lookup, lower(lookup, OptimizationLevel.BASELINE));
        assertSame(lookup, lowerWith(context(OptimizationLevel.PV11_SAFE, ValueLiteralFoldPass.RULE), lookup));

        // A wrapper bound once whose body is the builtin over its parameters (the NativeValueLib
        // shape): the call-site literals are substituted by position and the call folds.
        var union = new PirTerm.Lam("a", DATA, new PirTerm.Lam("b", DATA,
                app(builtin(DefaultFun.UnionValue), new PirTerm.Var("b", DATA), new PirTerm.Var("a", DATA))));
        var viaWrapper = new PirTerm.Let("un", union, app(new PirTerm.Var("un", DATA), constant(single), constant(value(entry(P, T, 2)))));
        var loweredWrapper = assertInstanceOf(PirTerm.Let.class, lower(viaWrapper, OptimizationLevel.PV11_SAFE));
        assertEquals(new PirTerm.Const(value(entry(P, T, 7))), loweredWrapper.body());
        assertEquivalent(viaWrapper);
        // A wrapper carrying constants in its body: the call site `mk 5` is shorter than the
        // literal it would become, so the objective keeps the call (the wrapper may stay live).
        var baked = new PirTerm.Lam("q", new PirType.IntegerType(),
                app(builtin(DefaultFun.InsertCoin), constant(Constant.byteString(P)), constant(Constant.byteString(T)),
                        new PirTerm.Var("q", new PirType.IntegerType()), constant(NativeValueSemantics.EMPTY)));
        var viaBaked = new PirTerm.Let("mk", baked, app(new PirTerm.Var("mk", DATA), constant(Constant.integer(5))));
        assertSame(viaBaked, lower(viaBaked, OptimizationLevel.PV11_SAFE));
        assertTrue(bits(Term.const_(single)) > bits(Term.apply(Term.var(1), Term.const_(Constant.integer(5)))));
        assertEquivalent(viaBaked);
        // A parameter the body never uses: the strict application still evaluates its argument,
        // so the chain is not a wrapper and an error, a trace or a runtime value there survives.
        var unused = new PirTerm.Lam("x", DATA, new PirTerm.Lam("q", new PirType.IntegerType(),
                app(builtin(DefaultFun.InsertCoin), constant(Constant.byteString(P)), constant(Constant.byteString(T)),
                        new PirTerm.Var("q", new PirType.IntegerType()), constant(NativeValueSemantics.EMPTY))));
        for (var dropped : List.of(new PirTerm.Error(DATA),
                new PirTerm.Trace(constant(Constant.string("m")), constant(Constant.integer(0))),
                new PirTerm.Var("y", DATA))) {
            var call = new PirTerm.Let("mk", unused, app(new PirTerm.Var("mk", DATA), dropped, constant(Constant.integer(5))));
            assertSame(call, lower(call, OptimizationLevel.PV11_SAFE), dropped.toString());
            if (!(dropped instanceof PirTerm.Var)) assertEquivalent(call);
        }
        var unusedLiteral = new PirTerm.Let("mk", unused, app(new PirTerm.Var("mk", DATA), constant(Constant.integer(0)), constant(Constant.integer(5))));
        assertSame(unusedLiteral, lower(unusedLiteral, OptimizationLevel.PV11_SAFE));
        // A used parameter fed a non-literal blocks the fold too.
        var usedRuntime = new PirTerm.Let("un", union, app(new PirTerm.Var("un", DATA), constant(single), new PirTerm.Var("y", DATA)));
        assertSame(usedRuntime, lower(usedRuntime, OptimizationLevel.PV11_SAFE));
        // The same name bound twice is not a wrapper.
        var shadowed = new PirTerm.Let("un", union, new PirTerm.Let("un", constant(Constant.integer(1)),
                app(new PirTerm.Var("un", DATA), constant(single), constant(single))));
        assertSame(shadowed, lower(shadowed, OptimizationLevel.PV11_SAFE));
        // Unsaturated: two of three arguments.
        var partial = app(builtin(DefaultFun.LookupCoin), constant(Constant.byteString(P)), constant(Constant.byteString(T)));
        assertSame(partial, lower(partial, OptimizationLevel.PV11_SAFE));

        // A literal local, and a local aliasing a literal, feed the calls below: a lookup through
        // the alias folds (an integer is shorter than the call). A Value-sized result through the
        // alias does not: an alias is never credited with the constant it names, and `v` keeps an
        // occurrence in the alias binding, so `unionValue(v, w)` is measured over two references.
        var viaAlias = new PirTerm.Let("v", constant(single), new PirTerm.Let("w", new PirTerm.Var("v", DATA),
                app(builtin(DefaultFun.LookupCoin), constant(Constant.byteString(P)), constant(Constant.byteString(T)), new PirTerm.Var("w", DATA))));
        var loweredAlias = assertInstanceOf(PirTerm.Let.class, lower(viaAlias, OptimizationLevel.PV11_SAFE));
        assertEquals(new PirTerm.Const(Constant.integer(5)), assertInstanceOf(PirTerm.Let.class, loweredAlias.body()).body());
        assertEquivalent(viaAlias);
        var local = new PirTerm.Let("v", constant(single), new PirTerm.Let("w", new PirTerm.Var("v", DATA),
                app(builtin(DefaultFun.UnionValue), new PirTerm.Var("v", DATA), new PirTerm.Var("w", DATA))));
        assertSame(local, lower(local, OptimizationLevel.PV11_SAFE));
        assertEquivalent(local);
        var rebound = new PirTerm.Let("v", constant(single), new PirTerm.Let("v", constant(Constant.integer(1)),
                app(builtin(DefaultFun.ScaleValue), constant(Constant.integer(2)), new PirTerm.Var("v", DATA))));
        assertSame(rebound, lower(rebound, OptimizationLevel.PV11_SAFE));

        // A literal local shared by two literal calls stands at each site as a reference, not as
        // its constant: folding either call would copy the 32-byte key into the call site while
        // the binding stays live for the other, so both stay (the second review's finding).
        var wideKey = value(entry(new byte[32], T, 1));
        var shared = new PirTerm.Let("v", constant(wideKey),
                app(builtin(DefaultFun.UnionValue),
                        app(builtin(DefaultFun.ScaleValue), constant(Constant.integer(2)), new PirTerm.Var("v", DATA)),
                        app(builtin(DefaultFun.ScaleValue), constant(Constant.integer(3)), new PirTerm.Var("v", DATA))));
        assertSame(shared, lower(shared, OptimizationLevel.PV11_SAFE));
        var scaleOfReference = Term.apply(Term.apply(Term.builtin(DefaultFun.ScaleValue), Term.const_(Constant.integer(2))), Term.var(1));
        var scaleOfConstant = Term.apply(Term.apply(Term.builtin(DefaultFun.ScaleValue), Term.const_(Constant.integer(2))), Term.const_(wideKey));
        var scaled = Term.const_(NativeValueSemantics.scaleValue(BigInteger.TWO, wideKey));
        assertTrue(bits(scaled) > bits(scaleOfReference));
        assertTrue(bits(scaled) <= bits(scaleOfConstant), "the measure the second review found approved the copy");
        System.out.println("VALUE_LITERAL_OBJECTIVE shared local: scaled literal=" + bits(scaled) + " scaleValue 2 v (reference)="
                + bits(scaleOfReference) + " scaleValue 2 <constant>=" + bits(scaleOfConstant) + " (bits)");
        assertEquivalent(shared);
        // Beside a runtime use of the local the literal call stays as well.
        var runtimeBeside = new PirTerm.Lam("n", new PirType.IntegerType(), new PirTerm.Let("v", constant(wideKey),
                app(builtin(DefaultFun.UnionValue),
                        app(builtin(DefaultFun.ScaleValue), new PirTerm.Var("n", new PirType.IntegerType()), new PirTerm.Var("v", DATA)),
                        app(builtin(DefaultFun.ScaleValue), constant(Constant.integer(2)), new PirTerm.Var("v", DATA)))));
        assertSame(runtimeBeside, lower(runtimeBeside, OptimizationLevel.PV11_SAFE));
        // One call consuming every use of the local (here both) dies with it: the local is
        // measured as its constant once, the call folds, the dead binding is left to the optimiser.
        var consumedTwice = new PirTerm.Let("v", constant(wideKey),
                app(builtin(DefaultFun.UnionValue), new PirTerm.Var("v", DATA), new PirTerm.Var("v", DATA)));
        assertEquals(new PirTerm.Const(value(entry(new byte[32], T, 2))),
                assertInstanceOf(PirTerm.Let.class, lower(consumedTwice, OptimizationLevel.PV11_SAFE)).body());
        assertEquivalent(consumedTwice);
        // The second review's alias case: the alias dies with its call but only its reference
        // binding disappears; `v` stays live for the other call, so nothing may copy its constant.
        var aliasBeside = new PirTerm.Let("v", constant(wideKey), new PirTerm.Let("w", new PirTerm.Var("v", DATA),
                app(builtin(DefaultFun.UnionValue),
                        app(builtin(DefaultFun.ScaleValue), constant(Constant.integer(2)), new PirTerm.Var("w", DATA)),
                        app(builtin(DefaultFun.ScaleValue), constant(Constant.integer(3)), new PirTerm.Var("v", DATA)))));
        assertSame(aliasBeside, lower(aliasBeside, OptimizationLevel.PV11_SAFE));
        assertEquivalent(aliasBeside);
        // An alias whose original has no other use is not credited either (the alias binding
        // keeps `v` occurring until the optimiser drops both): the documented conservative bound.
        var aliasOnly = new PirTerm.Let("v", constant(wideKey), new PirTerm.Let("w", new PirTerm.Var("v", DATA),
                app(builtin(DefaultFun.ScaleValue), constant(Constant.integer(2)), new PirTerm.Var("w", DATA))));
        assertSame(aliasOnly, lower(aliasOnly, OptimizationLevel.PV11_SAFE));
        assertEquivalent(aliasOnly);

        // A trace, an error or a runtime variable in argument position blocks the fold.
        var traced = app(builtin(DefaultFun.LookupCoin), constant(Constant.byteString(P)), constant(Constant.byteString(T)),
                new PirTerm.Trace(constant(Constant.string("m")), constant(single)));
        assertSame(traced, lower(traced, OptimizationLevel.PV11_SAFE));
        assertEquivalent(traced);
        var erroring = app(builtin(DefaultFun.ScaleValue), constant(Constant.integer(2)), new PirTerm.Error(new PirType.NativeValueType()));
        assertSame(erroring, lower(erroring, OptimizationLevel.PV11_SAFE));
        var runtime = new PirTerm.Lam("k", new PirType.ByteStringType(),
                app(builtin(DefaultFun.LookupCoin), new PirTerm.Var("k", new PirType.ByteStringType()), constant(Constant.byteString(T)), constant(single)));
        assertSame(runtime, lower(runtime, OptimizationLevel.PV11_SAFE));

        // Nested literal calls fold to a fixed point in one pass.
        var nested = app(builtin(DefaultFun.LookupCoin), constant(Constant.byteString(P)), constant(Constant.byteString(T)),
                app(builtin(DefaultFun.UnionValue),
                        app(builtin(DefaultFun.ScaleValue), constant(Constant.integer(3)), constant(single)),
                        app(builtin(DefaultFun.InsertCoin), constant(Constant.byteString(P)), constant(Constant.byteString(T)),
                                constant(Constant.integer(2)), constant(NativeValueSemantics.EMPTY))));
        assertEquals(new PirTerm.Const(Constant.integer(17)), lower(nested, OptimizationLevel.PV11_SAFE));
        assertEquivalent(nested);

        // Failing literal calls stay: overflow, negative containment, too long a key.
        var overflow = app(builtin(DefaultFun.UnionValue), constant(value(entry(P, T, MAX_QUANTITY))), constant(single));
        assertSame(overflow, lower(overflow, OptimizationLevel.PV11_SAFE));
        assertEquivalent(overflow);
        var negative = app(builtin(DefaultFun.ValueContains), constant(value(entry(P, T, BigInteger.valueOf(-1)))), constant(NativeValueSemantics.EMPTY));
        assertSame(negative, lower(negative, OptimizationLevel.PV11_SAFE));
        assertEquivalent(negative);
        var longKey = app(builtin(DefaultFun.InsertCoin), constant(Constant.byteString(new byte[33])), constant(Constant.byteString(T)),
                constant(Constant.integer(1)), constant(NativeValueSemantics.EMPTY));
        assertSame(longKey, lower(longKey, OptimizationLevel.PV11_SAFE));
        assertEquivalent(longKey);

        // Positions: the call's location moves to the literal.
        var positions = new IdentityHashMap<PirTerm, SourceLocation>();
        var at = new SourceLocation("Lit.java", 3, 7, "lookup");
        positions.put(lookup, at);
        var located = new ValueLiteralFoldPass(context(OptimizationLevel.PV11_SAFE), positions).lower(lookup);
        assertEquals(new PirTerm.Const(Constant.integer(5)), located.term());
        assertEquals(at, located.positions().get(located.term()));

        // The objective: a fold fires exactly when the literal's encoding is not longer, in bits,
        // than the direct builtin application's; measured for the Data conversions across entry counts.
        var emptyToData = app(builtin(DefaultFun.ValueData), constant(NativeValueSemantics.EMPTY));
        assertTrue(bits(Term.const_(Constant.data(PlutusData.map()))) > bits(Term.apply(Term.builtin(DefaultFun.ValueData), Term.const_(NativeValueSemantics.EMPTY))));
        assertSame(emptyToData, lower(emptyToData, OptimizationLevel.PV11_SAFE));
        System.out.println("VALUE_LITERAL_OBJECTIVE empty valueData call=" + bits(Term.apply(Term.builtin(DefaultFun.ValueData), Term.const_(NativeValueSemantics.EMPTY)))
                + " literal=" + bits(Term.const_(Constant.data(PlutusData.map()))) + " (bits)");
        for (int tokens = 1; tokens <= 12; tokens++) {
            var entries = new ArrayList<Constant.ValueConst.TokenEntry>();
            for (int i = 0; i < tokens; i++) entries.add(new Constant.ValueConst.TokenEntry(new byte[]{(byte) i}, BigInteger.valueOf(1000 + i)));
            var v = new Constant.ValueConst(List.of(new Constant.ValueConst.ValueEntry(P, entries)));
            var data = NativeValueSemantics.valueData(v);
            var toData = app(builtin(DefaultFun.ValueData), constant(v));
            var fromData = app(builtin(DefaultFun.UnValueData), constant(Constant.data(data)));
            boolean foldsToData = bits(Term.const_(Constant.data(data))) <= bits(Term.apply(Term.builtin(DefaultFun.ValueData), Term.const_(v)));
            boolean foldsFromData = bits(Term.const_(v)) <= bits(Term.apply(Term.builtin(DefaultFun.UnValueData), Term.const_(Constant.data(data))));
            assertEquals(foldsToData, lower(toData, OptimizationLevel.PV11_SAFE) instanceof PirTerm.Const, "valueData/" + tokens);
            assertEquals(foldsFromData, lower(fromData, OptimizationLevel.PV11_SAFE) instanceof PirTerm.Const, "unValueData/" + tokens);
            System.out.println("VALUE_LITERAL_OBJECTIVE tokens=" + tokens + " valueData=" + foldsToData + " unValueData=" + foldsFromData);
        }
    }

    /** The target check runs before any lowering: a pre-PV11 target fails closed with JULC0031. */
    @Test
    void nonPv11TargetFailsClosedBeforeLowering() {
        var pv10 = new CompilerTarget(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3), UplcVersion.V1_1_0);
        for (var fixture : List.of(FIXTURES.get(0), FIXTURES.get(1))) {
            var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setTarget(pv10)).compileMethod(fixture.source(), fixture.method()));
            assertEquals("JULC0031", error.diagnostics().getFirst().code(), fixture.name());
        }
    }

    // --- helpers ---

    /** The FLAT bit length of a bare term (no program header, no padding). */
    private static int bits(Term term) {
        var writer = new FlatWriter();
        new UplcFlatEncoder(writer).writeTerm(term);
        return writer.bitLength();
    }

    private static PirTerm unValue(PlutusData data) {
        return app(builtin(DefaultFun.UnValueData), constant(Constant.data(data)));
    }

    static PirTerm builtin(DefaultFun fun) { return new PirTerm.Builtin(fun); }
    static PirTerm constant(Constant value) { return new PirTerm.Const(value); }

    static PirTerm app(PirTerm function, PirTerm... args) {
        PirTerm term = function;
        for (var arg : args) term = new PirTerm.App(term, arg);
        return term;
    }

    static PlutusData bytes(int... values) {
        var out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return PlutusData.bytes(out);
    }

    static PlutusData.Pair entry(PlutusData key, PlutusData value) { return new PlutusData.Pair(key, value); }
    static PlutusData mapOf(PlutusData.Pair... entries) { return PlutusData.map(entries); }

    static Constant.ValueConst.ValueEntry entry(byte[] policy, byte[] token, long quantity) {
        return entry(policy, token, BigInteger.valueOf(quantity));
    }

    static Constant.ValueConst.ValueEntry entry(byte[] policy, byte[] token, BigInteger quantity) {
        return new Constant.ValueConst.ValueEntry(policy, List.of(new Constant.ValueConst.TokenEntry(token, quantity)));
    }

    static Constant.ValueConst value(Constant.ValueConst.ValueEntry... entries) {
        return new Constant.ValueConst(List.of(entries));
    }

    private static PirTerm lower(PirTerm term, OptimizationLevel level) {
        return lowerWith(context(level), term);
    }

    private static PirTerm lowerWith(CompilationContext context, PirTerm term) {
        return new ValueLiteralFoldPass(context, Map.of()).lower(term).term();
    }

    private static CompilationContext context(OptimizationLevel level, String... disabledRules) {
        var options = new CompilerOptions().setOptimizationLevel(level).setOptimizationCostProfile(PROFILE);
        for (var rule : disabledRules) options.disableOptimizationRule(rule);
        return CompilationContext.resolve(options);
    }

    /** BASELINE (never folded) and safe-profile programs of a closed term agree on every backend. */
    private static void assertEquivalent(PirTerm term) {
        var baseline = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE)).compilePirToProgram(term);
        var safe = new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE)).compilePirToProgram(term);
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

    /** Value builtin call sites in user code: outermost application spines headed by a Value builtin or a library wrapper. */
    static int countCalls(PirTerm term) {
        int[] count = {0};
        walkUser(term, false, (t, inFunctionPosition) -> {
            if (t instanceof PirTerm.App && !inFunctionPosition) {
                PirTerm head = t;
                while (head instanceof PirTerm.App a) head = a.function();
                if (head instanceof PirTerm.Builtin b && VALUE_BUILTINS.contains(b.fun())) count[0]++;
                if (head instanceof PirTerm.Var v && v.name().startsWith(LIB) && WRAPPERS.contains(v.name().substring(LIB.length()))) count[0]++;
            }
        });
        return count[0];
    }

    private interface Visit { void accept(PirTerm term, boolean inFunctionPosition); }

    private static void walkUser(PirTerm term, boolean inFunctionPosition, Visit visit) {
        visit.accept(term, inFunctionPosition);
        switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> { }
            case PirTerm.Lam l -> walkUser(l.body(), false, visit);
            case PirTerm.Let l -> {
                if (!l.name().contains(".")) walkUser(l.value(), false, visit);
                walkUser(l.body(), false, visit);
            }
            case PirTerm.LetRec r -> {
                r.bindings().forEach(b -> { if (!b.name().contains(".")) walkUser(b.value(), false, visit); });
                walkUser(r.body(), false, visit);
            }
            case PirTerm.App a -> { walkUser(a.function(), true, visit); walkUser(a.argument(), false, visit); }
            case PirTerm.IfThenElse i -> { walkUser(i.cond(), false, visit); walkUser(i.thenBranch(), false, visit); walkUser(i.elseBranch(), false, visit); }
            case PirTerm.Trace t -> { walkUser(t.message(), false, visit); walkUser(t.body(), false, visit); }
            case PirTerm.DataConstr c -> c.fields().forEach(f -> walkUser(f, false, visit));
            case PirTerm.DataMatch m -> { walkUser(m.scrutinee(), false, visit); m.branches().forEach(b -> walkUser(b.body(), false, visit)); }
            case PirTerm.ListMatch m -> { walkUser(m.scrutinee(), false, visit); walkUser(m.nilBranch(), false, visit); walkUser(m.consBranch(), false, visit); }
            case PirTerm.PairMatch m -> { walkUser(m.scrutinee(), false, visit); walkUser(m.body(), false, visit); }
            case PirTerm.IntegerCase c -> { walkUser(c.scrutinee(), false, visit); c.branches().forEach(b -> walkUser(b, false, visit)); }
        }
    }

    static CompileResult compile(O14ValueLiteralFixtures.Fixture fixture, OptimizationLevel level, boolean maps, String... disabledRules) {
        var options = new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(maps).setOptimizationCostProfile(PROFILE);
        for (var rule : disabledRules) options.disableOptimizationRule(rule);
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(fixture.source(), fixture.method());
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
}
