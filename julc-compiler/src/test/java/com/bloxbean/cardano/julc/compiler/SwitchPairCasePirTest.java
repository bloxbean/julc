package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.OptimizationCostProfiles;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.UplcVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises dispatch directly, without strict validator boundaries masking failures. */
@Tag("pair-case-backends")
class SwitchPairCasePirTest {
    private static final PirType INT = new PirType.IntegerType();
    private static final PirType DATA = new PirType.DataType();

    @Test
    void strictScrutineeAndSelectedBranchDecodingIncludingUnusedFields() {
        var branches = List.of(branch(List.of("x", "unused"), trace("body", var("x"))),
                branch(List.of(), trace("empty", integer(42))));
        for (var input : List.of(PlutusData.constr(0, PlutusData.integer(7), PlutusData.integer(9)),
                PlutusData.constr(0, PlutusData.integer(7), PlutusData.bytes(new byte[0])),
                PlutusData.constr(0), PlutusData.constr(1), PlutusData.constr(99), PlutusData.integer(0))) {
            var term = new PirTerm.DataMatch(trace("input", data(input)), branches);
            var result = equivalent(term);
            if (input.equals(PlutusData.constr(1))) {
                assertEquals(Term.const_(Constant.integer(42)), ((EvalResult.Success) result).resultTerm());
                assertEquals(List.of("input", "empty"), result.traces());
            } else if (input.equals(PlutusData.constr(0, PlutusData.integer(7), PlutusData.integer(9)))) {
                assertEquals(Term.const_(Constant.integer(7)), ((EvalResult.Success) result).resultTerm());
                assertEquals(List.of("input", "body"), result.traces());
            } else {
                assertInstanceOf(EvalResult.Failure.class, result);
                assertEquals(List.of("input"), result.traces());
            }
        }
        var failedInput = new PirTerm.Let("strict", trace("before-error", integer(0)), new PirTerm.Error(DATA));
        var failure = equivalent(new PirTerm.DataMatch(failedInput, branches));
        assertInstanceOf(EvalResult.Failure.class, failure);
        assertEquals(List.of("before-error"), failure.traces());
        var bodyError = equivalent(new PirTerm.DataMatch(trace("input", data(PlutusData.constr(0))),
                List.of(branch(List.of(), new PirTerm.Error(INT)), branch(List.of(), trace("unselected", integer(1))))));
        assertEquals(List.of("input"), bodyError.traces());
        assertInstanceOf(EvalResult.Failure.class, bodyError);
    }

    @Test
    void zeroSingleAndFieldlessBranchesPreserveHistoricalBehavior() {
        for (var branches : List.of(List.<PirTerm.MatchBranch>of(), List.of(branch(List.of(), integer(7))),
                List.of(branch(List.of(), integer(7)), branch(List.of(), integer(8))))) {
            for (var input : List.of(PlutusData.constr(0), PlutusData.constr(1), PlutusData.constr(99), PlutusData.integer(0))) {
                var result = equivalent(new PirTerm.DataMatch(data(input), branches));
                boolean success = input instanceof PlutusData.ConstrData c
                        && (branches.size() == 1 || (branches.size() == 2 && c.tag() < 2));
                assertEquals(success, result.isSuccess());
            }
        }
        // Raw DataMatch historically does not reject extra fields. Boundaries own exact arity.
        var extra = equivalent(new PirTerm.DataMatch(data(PlutusData.constr(99, PlutusData.integer(7), PlutusData.integer(8))),
                List.of(branch(List.of("x"), var("x")))));
        assertEquals(Term.const_(Constant.integer(7)), ((EvalResult.Success) extra).resultTerm());
    }

    @Test
    void nestedMatchesAndRecursiveClosuresRespectShadowingAndCapture() {
        var n = var("n");
        var funType = new PirType.FunType(INT, INT);
        var recur = new PirTerm.Var("recur", funType);
        var function = new PirTerm.Lam("n", INT, new PirTerm.IfThenElse(call2(DefaultFun.EqualsInteger, n, integer(0)),
                var("x"), call2(DefaultFun.AddInteger, var("x"), new PirTerm.App(recur,
                        call2(DefaultFun.SubtractInteger, n, integer(1))))));
        var inner = new PirTerm.DataMatch(data(PlutusData.constr(0, PlutusData.integer(3))),
                List.of(branch(List.of("x"), var("x"))));
        var body = new PirTerm.LetRec(List.of(new PirTerm.Binding("recur", function)),
                call2(DefaultFun.AddInteger, inner, new PirTerm.App(recur, integer(2))));
        var match = new PirTerm.DataMatch(data(PlutusData.constr(0, PlutusData.integer(7))), List.of(branch(List.of("x"), body)));
        assertEquals(Term.const_(Constant.integer(24)), ((EvalResult.Success) equivalent(match)).resultTerm());
        // A recursive binding shadows the field, and a pattern variable shadows an outer binding.
        var shadow = new PirTerm.LetRec(List.of(new PirTerm.Binding("x", new PirTerm.Lam("n", INT, n))),
                new PirTerm.App(new PirTerm.Var("x", funType), integer(11)));
        assertEquals(Term.const_(Constant.integer(11)), ((EvalResult.Success) equivalent(
                new PirTerm.DataMatch(data(PlutusData.constr(0, PlutusData.integer(7))), List.of(branch(List.of("x"), shadow))))).resultTerm());
        var pattern = new PirTerm.MatchBranch("A", List.of(), List.of(), new PirTerm.Var("p", DATA), "p");
        var value = PlutusData.constr(0);
        var shadowPattern = new PirTerm.Let("p", data(PlutusData.integer(99)), new PirTerm.DataMatch(data(value), List.of(pattern)));
        assertEquals(Term.const_(Constant.data(value)), ((EvalResult.Success) equivalent(shadowPattern)).resultTerm());
    }

    @Test
    void removedPrivateBinderReferenceRetainsLegacyExpansion() {
        var pair = new PirTerm.Var("__match_pair", DATA);
        var term = new PirTerm.DataMatch(data(PlutusData.constr(0)),
                List.of(branch(List.of(), new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), pair))));
        for (var level : OptimizationLevel.values()) {
            var context = context(level);
            var generated = new UplcGenerator(context, null).generate(term);
            assertFalse(context.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_PAIR_RULE));
            assertFalse(generated.toString().contains("Case["));
        }
        assertEquals(Term.const_(Constant.integer(0)), ((EvalResult.Success) equivalent(term)).resultTerm());
    }

    @Test
    void exactProfileProvenanceSourceMapsAndPublicPirEntryPoint() {
        var term = new PirTerm.DataMatch(data(PlutusData.constr(0, PlutusData.integer(7))),
                List.of(branch(List.of("x"), var("x"))));
        var location = new SourceLocation("Switch.java", 12, 5, "switch dispatch");
        var positions = new IdentityHashMap<PirTerm, SourceLocation>();
        positions.put(term, location);
        var branchLocation = new SourceLocation("Switch.java", 14, 9, "selected body");
        positions.put(term.branches().getFirst().body(), branchLocation);
        for (var level : OptimizationLevel.values()) {
            var context = context(level);
            var generator = new UplcGenerator(context, positions);
            var generated = generator.generate(term);
            assertEquals(location, generator.getUplcPositions().get(generated));
            assertTrue(generator.getUplcPositions().containsValue(branchLocation));
            assertEquals(level.pv11SafeRulesEnabled(), context.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_PAIR_RULE));
            if (level.pv11SafeRulesEnabled()) {
                var pairCase = assertInstanceOf(Term.Case.class, ((Term.Lam) ((Term.Apply) generated).function()).body());
                assertEquals(location, generator.getUplcPositions().get(pairCase));
                assertEquals(1, pairCase.branches().size());
                assertInstanceOf(Term.Lam.class, ((Term.Lam) pairCase.branches().getFirst()).body());
            }
            var compiler = new JulcCompiler(null, options(level));
            var result = evaluate(compiler.compilePirToProgram(term), "Java");
            assertEquals(Term.const_(Constant.integer(7)), ((EvalResult.Success) result).resultTerm());
        }
    }

    @Test
    void unsupportedTargetCannotReachTheProducer() {
        for (var target : List.of(new CompilerTarget(LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3), UplcVersion.V1_1_0),
                new CompilerTarget(CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), UplcVersion.V1_0_0))) {
            assertThrows(CompilerException.class, () -> CompilationContext.resolve(
                    new CompilerOptions().setTarget(target).setOptimizationLevel(OptimizationLevel.PV11_SAFE)));
        }
    }

    private static EvalResult equivalent(PirTerm term) {
        EvalResult javaResult = null;
        for (String provider : List.of("Java", "Truffle", "Scalus")) {
            var before = evaluate(Program.plutusV3(new UplcGenerator(context(OptimizationLevel.BASELINE), null).generate(term)), provider);
            var after = evaluate(Program.plutusV3(new UplcGenerator(context(OptimizationLevel.PV11_SAFE), null).generate(term)), provider);
            SwitchPairCaseLoweringTest.assertEquivalent(before, after, provider);
            if (provider.equals("Java")) javaResult = after;
            if (provider.equals("Truffle")) assertEquals(javaResult.budgetConsumed(), after.budgetConsumed());
            if (!provider.equals("Scalus")) {
                assertTrue(after.budgetConsumed().cpuSteps() <= before.budgetConsumed().cpuSteps());
                assertTrue(after.budgetConsumed().memoryUnits() <= before.budgetConsumed().memoryUnits());
            }
        }
        return javaResult;
    }

    private static EvalResult evaluate(Program program, String provider) {
        var vm = CompilerTestVm.pv11(provider);
        return provider.equals("Scalus") ? vm.evaluate(program)
                : vm.evaluate(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), null, EvalOptions.DEFAULT);
    }
    private static CompilerOptions options(OptimizationLevel level) { return new CompilerOptions().setOptimizationLevel(level)
            .setOptimizationCostProfile(OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11); }
    private static CompilationContext context(OptimizationLevel level) { return CompilationContext.resolve(options(level)); }
    private static PirTerm.MatchBranch branch(List<String> names, PirTerm body) {
        return new PirTerm.MatchBranch("A", names, names.stream().map(_ -> INT).toList(), body);
    }
    private static PirTerm integer(long value) { return new PirTerm.Const(Constant.integer(value)); }
    private static PirTerm data(PlutusData value) { return new PirTerm.Const(Constant.data(value)); }
    private static PirTerm var(String name) { return new PirTerm.Var(name, INT); }
    private static PirTerm trace(String message, PirTerm body) { return new PirTerm.Trace(new PirTerm.Const(Constant.string(message)), body); }
    private static PirTerm call2(DefaultFun fun, PirTerm a, PirTerm b) { return new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(fun), a), b); }
}
