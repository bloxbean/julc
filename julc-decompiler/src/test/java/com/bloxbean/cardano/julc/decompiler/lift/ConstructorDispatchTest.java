package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.compiler.CompilationContext;
import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.OptimizationLevel;
import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.decompiler.DecompileOptions;
import com.bloxbean.cardano.julc.decompiler.JulcDecompiler;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConstructorDispatchTest {
    @Test
    void strictRecognitionChecksIndicesForceCountsAndExactProducerShape() {
        var condition = call(DefaultFun.EqualsInteger, Term.var(2), integer(0));
        var dispatch = Term.case_(condition, Term.error(), Term.var(1));
        var nativeMatch = nativeMatch(data(PlutusData.constr(0)), dispatch);
        var match = DataMatchRecognizer.matchConstructorDispatch(nativeMatch);
        assertNotNull(match);
        assertEquals(BigInteger.ZERO, match.branches().getFirst().tag());
        assertSame(((Term.Case) dispatch).branches().get(1), match.branches().getFirst().body());
        assertInstanceOf(Term.Error.class, match.fallback());
        assertNotNull(DataMatchRecognizer.matchConstructorDispatch(legacy(data(PlutusData.constr(0)), dispatch, 1, 2)));
        var malformedChooser = Term.force(Term.apply(Term.apply(Term.apply(
                Term.force(Term.force(Term.builtin(DefaultFun.IfThenElse))), condition), Term.delay(integer(7))), Term.delay(Term.error())));
        assertNull(DataMatchRecognizer.matchConstructorDispatch(nativeMatch(data(PlutusData.constr(0)), malformedChooser)));
        var wrongProjectionForces = Term.apply(Term.lam("pair", Term.apply(Term.lam("tag",
                        Term.apply(Term.lam("fields", dispatch), call(DefaultFun.SndPair, Term.var(2)))),
                Term.apply(Term.force(Term.builtin(DefaultFun.FstPair)), Term.var(1)))), call(DefaultFun.UnConstrData, data(PlutusData.constr(0))));
        assertNull(DataMatchRecognizer.matchConstructorDispatch(wrongProjectionForces));
        for (Term wrong : List.of(
                Term.case_(data(PlutusData.constr(0)), Term.lam("tag", Term.lam("fields", dispatch))),
                Term.case_(call(DefaultFun.UnMapData, data(PlutusData.map())), Term.lam("tag", Term.lam("fields", dispatch))),
                Term.case_(call(DefaultFun.UnConstrData, data(PlutusData.constr(0))), Term.lam("tag", dispatch)),
                Term.case_(call(DefaultFun.UnConstrData, data(PlutusData.constr(0))), Term.lam("t", Term.lam("f", dispatch)), Term.error()),
                nativeMatch(data(PlutusData.constr(0)), Term.case_(call(DefaultFun.EqualsInteger, Term.var(1), integer(0)), Term.error(), integer(7))),
                nativeMatch(data(PlutusData.constr(0)), Term.case_(call(DefaultFun.EqualsInteger, Term.var(3), integer(0)), Term.error(), integer(7))),
                legacy(data(PlutusData.constr(0)), dispatch, 2, 2),
                legacy(data(PlutusData.constr(0)), dispatch, 1, 1))) {
            assertNull(DataMatchRecognizer.matchConstructorDispatch(wrong), wrong.toString());
        }
    }

    @Test
    void recoversHugeReversedTagsAndRetainsResidualFallbackByIdentity() {
        var huge = BigInteger.ONE.shiftLeft(70);
        var fallback = trace("fallback", integer(19));
        var dispatch = Term.case_(call(DefaultFun.EqualsInteger, Term.const_(Constant.integer(huge)), Term.var(2)),
                fallback, integer(7));
        var match = DataMatchRecognizer.matchConstructorDispatch(nativeMatch(data(PlutusData.constr(0)), dispatch));
        assertEquals(huge, match.branches().getFirst().tag());
        assertSame(fallback, match.fallback());
        var rendered = JulcDecompiler.decompile(Program.plutusV3(nativeMatch(data(PlutusData.constr(0)), dispatch)),
                DecompileOptions.defaults()).javaSource();
        assertTrue(rendered.contains(".equals(new BigInteger(\"" + huge + "\"))"), rendered);
        assertEquals(Term.const_(Constant.integer(19)), ((EvalResult.Success) equivalent(
                nativeMatch(data(PlutusData.constr(0)), dispatch))).resultTerm());
    }

    @Test
    void preservesStrictDecodeAndBranchLazinessWithMalformedInputs() {
        var first = call(DefaultFun.UnIData, call(DefaultFun.HeadList, Term.var(1)));
        var second = call(DefaultFun.UnIData, call(DefaultFun.HeadList, call(DefaultFun.TailList, Term.var(2))));
        var body = Term.apply(Term.lam("x", Term.apply(Term.lam("unused", trace("selected", Term.var(2))), second)), first);
        var dispatch = Term.case_(call(DefaultFun.EqualsInteger, Term.var(2), integer(0)), trace("fallback", integer(42)), body);
        for (var input : List.of(PlutusData.constr(0, PlutusData.integer(7), PlutusData.integer(9)),
                PlutusData.constr(0, PlutusData.integer(7), PlutusData.bytes(new byte[0])),
                PlutusData.constr(0), PlutusData.constr(1), PlutusData.integer(0))) {
            for (boolean nativeCase : List.of(false, true)) {
                var scrutinee = trace("input", data(input));
                var term = nativeCase ? nativeMatch(scrutinee, dispatch) : legacy(scrutinee, dispatch, 1, 2);
                var result = equivalent(term);
                if (input.equals(PlutusData.constr(1))) assertEquals(List.of("input", "fallback"), result.traces());
                else if (input.equals(PlutusData.constr(0, PlutusData.integer(7), PlutusData.integer(9)))) {
                    assertEquals(Term.const_(Constant.integer(7)), ((EvalResult.Success) result).resultTerm());
                    assertEquals(List.of("input", "selected"), result.traces());
                } else {
                    assertInstanceOf(EvalResult.Failure.class, result);
                    assertEquals(List.of("input"), result.traces());
                }
            }
        }
        assertInstanceOf(EvalResult.Failure.class, equivalent(nativeMatch(Term.error(), dispatch)));
    }

    @Test
    void singletonDoesNotInventTagZeroAndKeepsCapturedNamesAcrossNestedMatches() {
        var singleton = nativeMatch(data(PlutusData.constr(99)), Term.var(2));
        var recovered = assertInstanceOf(HirTerm.DataMatch.class, UplcLifter.lift(singleton));
        assertTrue(recovered.branches().isEmpty());
        assertEquals(Term.const_(Constant.integer(99)), ((EvalResult.Success) equivalent(singleton)).resultTerm());
        // Both matches reuse debug names; beneath the extra user lambda, the outer tag is index 5.
        var inner = nativeMatch(data(PlutusData.constr(5)), call(DefaultFun.AddInteger, Term.var(2), Term.var(5)));
        var outer = nativeMatch(data(PlutusData.constr(7)), Term.apply(Term.lam("tag", inner), integer(100)));
        assertEquals(Term.const_(Constant.integer(12)), ((EvalResult.Success) equivalent(outer)).resultTerm());
        var closure = nativeMatch(data(PlutusData.constr(8)), Term.lam("tag", call(DefaultFun.AddInteger, Term.var(1), Term.var(3))));
        var applied = Term.apply(closure, integer(4));
        assertEquals(Term.const_(Constant.integer(12)), ((EvalResult.Success) equivalent(applied)).resultTerm());
    }

    @Test
    void capturesFieldsAndLegacyPairAndMatchesInsideScrutinee() {
        var fieldsClosure = nativeMatch(data(PlutusData.constr(0, PlutusData.integer(9))),
                Term.lam("fields", call(DefaultFun.UnIData, call(DefaultFun.HeadList, Term.var(2)))));
        assertEquals(integer(9), ((EvalResult.Success) equivalent(Term.apply(fieldsClosure, integer(100)))).resultTerm());
        var inner = nativeMatch(data(PlutusData.constr(5)), Term.var(4));
        assertEquals(integer(7), ((EvalResult.Success) equivalent(legacy(data(PlutusData.constr(7)), inner, 1, 2))).resultTerm());
        var pairUse = legacy(data(PlutusData.constr(12)), call(DefaultFun.FstPair, Term.var(3)), 1, 2);
        assertEquals(integer(12), ((EvalResult.Success) equivalent(pairUse)).resultTerm());
        var input = nativeMatch(trace("inner", data(PlutusData.constr(0))), data(PlutusData.constr(7)));
        assertEquals(integer(7), ((EvalResult.Success) equivalent(nativeMatch(input, Term.var(2)))).resultTerm());
    }

    @Test
    void duplicateTagsAndErrorsKeepOrderedDispatchAndRenderingKeepsFallback() {
        var dispatch = Term.case_(call(DefaultFun.EqualsInteger, Term.var(2), integer(0)),
                Term.case_(call(DefaultFun.EqualsInteger, Term.var(2), integer(0)), trace("unknown", Term.error()), Term.error()), integer(7));
        var term = nativeMatch(data(PlutusData.constr(0)), dispatch);
        assertEquals(2, DataMatchRecognizer.matchConstructorDispatch(term).branches().size());
        assertEquals(integer(7), ((EvalResult.Success) equivalent(term)).resultTerm());
        assertInstanceOf(EvalResult.Failure.class, equivalent(nativeMatch(data(PlutusData.constr(99)), dispatch)));
        var source = JulcDecompiler.decompile(Program.plutusV3(term), DecompileOptions.defaults()).javaSource();
        assertTrue(source.contains("unConstrData"), source);
        assertTrue(source.contains("fstPair"), source);
        assertTrue(source.contains("sndPair"), source);
        assertTrue(source.contains("unknown"), source);
        assertTrue(source.contains("Builtins.error()"), source);
        assertFalse(source.contains("case Case0"), source);
    }

    static EvalResult equivalent(Term term) {
        EvalResult result = null;
        for (var input : List.of(term, UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(Program.plutusV3(term))).term())) {
            assertArrayEquals(UplcFlatEncoder.encodeProgram(Program.plutusV3(input)),
                    UplcFlatEncoder.encodeProgram(Program.plutusV3(ScopedNames.resolve(input))));
            for (var level : List.of(DecompileOptions.OutputLevel.STRUCTURED, DecompileOptions.OutputLevel.TYPED)) {
                var options = new DecompileOptions(level, null);
                var hir = JulcDecompiler.decompile(Program.plutusV3(input), options).hir();
                var restored = new UplcGenerator(CompilationContext.resolve(new CompilerOptions()
                        .setOptimizationLevel(OptimizationLevel.BASELINE)), null).generate(toPir(hir));
                for (String provider : List.of("Java", "Scalus")) {
                    var vm = JulcVm.create(provider);
                    var target = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
                    var before = provider.equals("Java") ? vm.evaluate(Program.plutusV3(input), target, null, EvalOptions.DEFAULT)
                            : vm.evaluate(Program.plutusV3(input));
                    result = provider.equals("Java") ? vm.evaluate(Program.plutusV3(restored), target, null, EvalOptions.DEFAULT)
                            : vm.evaluate(Program.plutusV3(restored));
                    assertEquals(before.getClass(), result.getClass(), provider);
                    assertEquals(before.traces(), result.traces(), provider);
                    if (before instanceof EvalResult.Success s) assertEquals(s.resultTerm(), ((EvalResult.Success) result).resultTerm(), provider);
                    if (before instanceof EvalResult.Failure f) assertEquals(f.error(), ((EvalResult.Failure) result).error(), provider);
                }
            }
        }
        return result;
    }

    // Bounded reconstruction oracle; unsupported HIR fails the test instead of silently approximating it.
    private static PirTerm toPir(HirTerm h) {
        var data = new PirType.DataType();
        return switch (h) {
            case HirTerm.Var v -> new PirTerm.Var(v.name(), data);
            case HirTerm.IntLiteral n -> new PirTerm.Const(Constant.integer(n.value()));
            case HirTerm.BoolLiteral b -> new PirTerm.Const(Constant.bool(b.value()));
            case HirTerm.DataLiteral d -> new PirTerm.Const(Constant.data(d.value()));
            case HirTerm.StringLiteral s -> new PirTerm.Const(Constant.string(s.value()));
            case HirTerm.Error _ -> new PirTerm.Error(data);
            case HirTerm.Let l -> new PirTerm.Let(l.name(), toPir(l.value()), toPir(l.body()));
            case HirTerm.Lambda l -> {
                var body = toPir(l.body());
                for (var p : l.params().reversed()) body = new PirTerm.Lam(p, data, body);
                yield body;
            }
            case HirTerm.If i -> new PirTerm.IfThenElse(toPir(i.condition()), toPir(i.thenBranch()), toPir(i.elseBranch()));
            case HirTerm.Trace t -> new PirTerm.Trace(toPir(t.message()), toPir(t.body()));
            case HirTerm.DataDecode d -> pirCall(d.decoder(), toPir(d.operand()));
            case HirTerm.DataEncode d -> pirCall(d.encoder(), toPir(d.operand()));
            case HirTerm.BuiltinCall b -> pirCall(b.fun(), b.args().stream().map(ConstructorDispatchTest::toPir).toArray(PirTerm[]::new));
            case HirTerm.FunCall f -> {
                var args = f.args().stream().map(ConstructorDispatchTest::toPir).toList();
                PirTerm call = f.name().equals("_apply") ? args.getFirst() : new PirTerm.Var(f.name(), data);
                for (var a : f.name().equals("_apply") ? args.subList(1, args.size()) : args) call = new PirTerm.App(call, a);
                yield call;
            }
            case HirTerm.DataMatch m -> {
                var fallback = toPir(m.fallback());
                for (var b : m.branches().reversed()) fallback = new PirTerm.IfThenElse(pirCall(DefaultFun.EqualsInteger,
                        new PirTerm.Var(m.tagName(), data), new PirTerm.Const(Constant.integer(b.tag()))), toPir(b.body()), fallback);
                var pair = new PirTerm.Var(m.pairName(), data);
                yield new PirTerm.Let(m.pairName(), pirCall(DefaultFun.UnConstrData, toPir(m.scrutinee())),
                        new PirTerm.Let(m.tagName(), pirCall(DefaultFun.FstPair, pair),
                                new PirTerm.Let(m.fieldsName(), pirCall(DefaultFun.SndPair, pair), fallback)));
            }
            default -> throw new AssertionError("Unsupported reconstruction: " + h);
        };
    }

    private static PirTerm pirCall(DefaultFun f, PirTerm... args) {
        PirTerm t = new PirTerm.Builtin(f);
        for (var a : args) t = new PirTerm.App(t, a);
        return t;
    }
    static Term nativeMatch(Term data, Term body) { return Term.case_(call(DefaultFun.UnConstrData, data), Term.lam("tag", Term.lam("fields", body))); }
    static Term legacy(Term data, Term body, int first, int second) {
        return Term.apply(Term.lam("pair", Term.apply(Term.lam("tag", Term.apply(Term.lam("fields", body),
                call(DefaultFun.SndPair, Term.var(second)))), call(DefaultFun.FstPair, Term.var(first)))), call(DefaultFun.UnConstrData, data));
    }
    static Term call(DefaultFun f, Term... args) {
        Term t = Term.builtin(f);
        int forces = switch (f) { case FstPair, SndPair -> 2; case HeadList, TailList, NullList, Trace, IfThenElse -> 1; default -> 0; };
        for (int i = 0; i < forces; i++) t = Term.force(t);
        for (var a : args) t = Term.apply(t, a);
        return t;
    }
    static Term integer(long n) { return Term.const_(Constant.integer(n)); }
    static Term data(PlutusData d) { return Term.const_(Constant.data(d)); }
    static Term trace(String message, Term body) { return call(DefaultFun.Trace, Term.const_(Constant.string(message)), body); }
}
