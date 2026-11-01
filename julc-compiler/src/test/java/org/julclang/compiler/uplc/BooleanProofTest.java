package org.julclang.compiler.uplc;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.uplc.BooleanProof.Scope;
import org.julclang.core.BuiltinSemantics;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BooleanProofTest {

    private static final PirType DATA = new PirType.DataType();
    private static final PirType INT = new PirType.IntegerType();
    private static final PirType BOOL = new PirType.BoolType();

    private static final PirTerm TRUE = new PirTerm.Const(Constant.bool(true));
    private static final PirTerm FALSE = new PirTerm.Const(Constant.bool(false));
    private static final PirTerm ONE = new PirTerm.Const(Constant.integer(BigInteger.ONE));
    private static final PirTerm ZERO = new PirTerm.Const(Constant.integer(BigInteger.ZERO));
    private static final PirTerm ERROR = new PirTerm.Error(BOOL);

    private static boolean proven(PirTerm term) {
        return BooleanProof.proves(term, Scope.EMPTY);
    }

    private static PirTerm var(String name) {
        return new PirTerm.Var(name, BOOL);
    }

    private static PirTerm lam(String param, PirTerm body) {
        // The parameter's declared type is boolean throughout: a declared type is no proof.
        return new PirTerm.Lam(param, BOOL, body);
    }

    private static PirTerm app(PirTerm function, PirTerm... arguments) {
        var term = function;
        for (var argument : arguments) term = new PirTerm.App(term, argument);
        return term;
    }

    private static PirTerm let(String name, PirTerm value, PirTerm body) {
        return new PirTerm.Let(name, value, body);
    }

    private static PirTerm ifThenElse(PirTerm cond, PirTerm thenBranch, PirTerm elseBranch) {
        return new PirTerm.IfThenElse(cond, thenBranch, elseBranch);
    }

    private static PirTerm builtin(DefaultFun fun) {
        return new PirTerm.Builtin(fun);
    }

    private static PirTerm equalsInteger(PirTerm a, PirTerm b) {
        return app(builtin(DefaultFun.EqualsInteger), a, b);
    }

    private static PirTerm letRec(String name, PirTerm value, PirTerm body) {
        return new PirTerm.LetRec(List.of(new PirTerm.Binding(name, value)), body);
    }

    private static PirTerm unConstr(PirTerm data) {
        return app(builtin(DefaultFun.UnConstrData), data);
    }

    private static final PirType.PairType PAIR = new PirType.PairType(INT, new PirType.ListType(DATA));

    @Nested
    class Constants {
        @Test void booleanConstants() {
            assertTrue(proven(TRUE));
            assertTrue(proven(FALSE));
        }

        @Test void otherConstants() {
            assertFalse(proven(ONE));
            assertFalse(proven(ZERO));
            assertFalse(proven(new PirTerm.Const(Constant.unit())));
            assertFalse(proven(new PirTerm.Const(Constant.byteString(new byte[]{1}))));
            assertFalse(proven(new PirTerm.Const(Constant.data(new PlutusData.ConstrData(1, List.of())))));
        }
    }

    @Nested
    class Builtins {
        @ParameterizedTest
        @EnumSource(DefaultFun.class)
        void aSaturatedBuiltinIsProvenExactlyWhenItsResultIsBoolean(DefaultFun fun) {
            var arguments = new PirTerm[BuiltinSemantics.find(fun).valueArity()];
            Arrays.fill(arguments, ONE);
            assertEquals(BooleanProof.returnsBoolean(fun), proven(app(builtin(fun), arguments)), fun.name());
        }

        @ParameterizedTest
        @EnumSource(DefaultFun.class)
        void anUnsaturatedBuiltinIsNeverProven(DefaultFun fun) {
            var arguments = new PirTerm[BuiltinSemantics.find(fun).valueArity() - 1];
            Arrays.fill(arguments, ONE);
            assertFalse(proven(app(builtin(fun), arguments)), fun.name());
        }

        @ParameterizedTest
        @EnumSource(DefaultFun.class)
        void anOverAppliedBuiltinIsNeverProven(DefaultFun fun) {
            var arguments = new PirTerm[BuiltinSemantics.find(fun).valueArity() + 1];
            Arrays.fill(arguments, ONE);
            assertFalse(proven(app(builtin(fun), arguments)), fun.name());
        }

        @Test void thePolymorphicBuiltinThatReturnsItsArgumentsIsNotProven() {
            assertFalse(proven(app(builtin(DefaultFun.IfThenElse), TRUE, TRUE, FALSE)));
            assertFalse(proven(app(builtin(DefaultFun.Trace), ONE, TRUE)));
            assertFalse(proven(app(builtin(DefaultFun.HeadList), ONE)));
            assertFalse(proven(app(builtin(DefaultFun.ChooseUnit), ONE, TRUE)));
        }

        @Test void aPartialApplicationIsCompletedThroughALet() {
            assertTrue(proven(let("eq", app(builtin(DefaultFun.EqualsInteger), ONE), app(var("eq"), ZERO))));
            assertFalse(proven(let("eq", app(builtin(DefaultFun.EqualsInteger), ONE), var("eq"))));
        }

        @Test void decodedBooleansNeedNoSpecialCase() {
            assertTrue(proven(PirHelpers.wrapDecode(new PirTerm.Var("d", DATA), BOOL)));
        }
    }

    @Nested
    class Conditionals {
        @Test void bothArmsProven() {
            assertTrue(proven(ifThenElse(var("anything"), TRUE, equalsInteger(ONE, ZERO))));
        }

        @Test void oneArmUnproven() {
            assertFalse(proven(ifThenElse(TRUE, TRUE, ONE)));
            assertFalse(proven(ifThenElse(TRUE, ONE, TRUE)));
            assertFalse(proven(ifThenElse(TRUE, var("p"), TRUE)));
        }

        @Test void anArmThatCannotProduceAValueDoesNotCount() {
            assertTrue(proven(ifThenElse(TRUE, TRUE, ERROR)));
            assertTrue(proven(ifThenElse(TRUE, ERROR, TRUE)));
            assertFalse(proven(ifThenElse(TRUE, ONE, ERROR)));
        }

        @Test void armsOfDifferentArityAreUnproven() {
            assertFalse(proven(app(ifThenElse(TRUE, lam("x", TRUE), TRUE), ONE)));
            assertFalse(proven(app(ifThenElse(TRUE, lam("x", TRUE), lam("x", lam("y", TRUE))), ONE)));
            assertTrue(proven(app(ifThenElse(TRUE, lam("x", TRUE), lam("y", FALSE)), ONE)));
        }
    }

    @Nested
    class Lets {
        @Test void theBodyDecides() {
            assertTrue(proven(let("x", ONE, TRUE)));
            assertFalse(proven(let("x", TRUE, ONE)));
        }

        @Test void aVariableHoldsWhatItsLetBound() {
            assertTrue(proven(let("x", TRUE, var("x"))));
            assertTrue(proven(let("x", equalsInteger(ONE, ONE), var("x"))));
            assertFalse(proven(let("x", ONE, var("x"))));
        }

        @Test void anAliasCarriesTheProof() {
            assertTrue(proven(let("x", TRUE, let("y", var("x"), var("y")))));
            assertFalse(proven(let("x", ONE, let("y", var("x"), var("y")))));
        }

        @Test void anUnboundVariableIsUnproven() {
            assertFalse(proven(var("nowhere")));
        }

        @Test void aLambdaParameterIsNeverProvenWhateverItsType() {
            assertFalse(proven(app(lam("x", var("x")), TRUE)));
        }

        @Test void traceReturnsItsBody() {
            var message = new PirTerm.Const(Constant.string("m"));
            assertTrue(proven(new PirTerm.Trace(message, TRUE)));
            assertFalse(proven(new PirTerm.Trace(message, ONE)));
        }

        @Test void errorProducesNoValue() {
            assertTrue(proven(ERROR));
        }
    }

    @Nested
    class Calls {
        @Test void aCallOfAKnownFunctionWithAProvenResult() {
            assertTrue(proven(let("f", lam("x", TRUE), app(var("f"), ONE))));
            assertTrue(proven(let("f", lam("x", lam("y", equalsInteger(var("x"), var("y")))),
                    app(var("f"), ONE, ZERO))));
        }

        @Test void aCallWhoseResultIsItsParameter() {
            assertFalse(proven(let("f", lam("x", var("x")), app(var("f"), TRUE))));
        }

        @Test void aFunctionWithAnUnprovenPathIsUnproven() {
            assertFalse(proven(let("f", lam("x", ifThenElse(var("x"), TRUE, var("x"))), app(var("f"), TRUE))));
        }

        @Test void underAndOverApplicationAreUnproven() {
            var f = lam("x", lam("y", TRUE));
            assertFalse(proven(let("f", f, var("f"))));
            assertFalse(proven(let("f", f, app(var("f"), ONE))));
            assertFalse(proven(let("f", f, app(var("f"), ONE, ONE, ONE))));
        }

        @Test void aFunctionIsNotABoolean() {
            assertFalse(proven(lam("x", TRUE)));
        }

        @Test void letsBetweenTheParametersAreReadThrough() {
            assertTrue(proven(let("f", lam("x", let("d", ONE, lam("y", TRUE))), app(var("f"), ONE, ONE))));
            assertTrue(proven(let("f", let("k", ONE, lam("x", TRUE)), app(var("f"), ONE))));
        }

        @Test void anAliasOfAFunction() {
            assertTrue(proven(let("f", lam("x", TRUE), let("g", var("f"), app(var("g"), ONE)))));
        }

        @Test void anArgumentHasNoEffect() {
            assertTrue(proven(let("f", lam("x", TRUE), app(var("f"), ERROR))));
        }

        @Test void aRedexIsAFunctionOfUnknownParameter() {
            assertTrue(proven(app(lam("x", TRUE), ONE)));
            assertFalse(proven(app(lam("x", var("x")), TRUE)));
        }
    }

    @Nested
    class Recursion {
        @Test void aRecursiveFunctionWhoseBaseCaseIsProven() {
            var f = lam("x", ifThenElse(var("c"), TRUE, app(var("f"), var("x"))));
            assertTrue(proven(letRec("f", f, app(var("f"), ONE))));
        }

        @Test void aRecursiveFunctionWhoseBaseCaseIsItsParameter() {
            var f = lam("x", ifThenElse(var("c"), var("x"), app(var("f"), var("x"))));
            assertFalse(proven(letRec("f", f, app(var("f"), TRUE))));
        }

        @Test void aRecursiveCallOfTheWrongArityIsUnproven() {
            var f = lam("x", ifThenElse(var("c"), TRUE, var("f")));
            assertFalse(proven(letRec("f", f, app(var("f"), ONE))));
        }

        @Test void aRecursiveFunctionReturningItselfIsUnproven() {
            assertFalse(proven(letRec("f", lam("x", var("f")), app(var("f"), ONE))));
        }

        @Test void aFunctionOnlyCallingItselfProducesNoValue() {
            assertTrue(proven(letRec("f", lam("x", app(var("f"), var("x"))), app(var("f"), ONE))));
        }

        @Test void aRecursiveBindingThatIsNotAFunctionIsUnproven() {
            // Its recursive reference is an eta-expansion, a lambda: the binding's value is not a boolean.
            assertFalse(proven(letRec("x", ifThenElse(var("c"), TRUE, var("x")), var("x"))));
            assertFalse(proven(letRec("x", TRUE, var("x"))));
        }

        @Test void mutualRecursion() {
            var even = lam("n", ifThenElse(var("c"), TRUE, app(var("odd"), var("n"))));
            var odd = lam("n", ifThenElse(var("c"), FALSE, app(var("even"), var("n"))));
            var group = List.of(new PirTerm.Binding("even", even), new PirTerm.Binding("odd", odd));
            assertTrue(proven(new PirTerm.LetRec(group, app(var("even"), ONE))));
            assertTrue(proven(new PirTerm.LetRec(group, app(var("odd"), ONE))));
        }

        @Test void anUnprovenPathReachesTheWholeMutualGroup() {
            var even = lam("n", ifThenElse(var("c"), TRUE, app(var("odd"), var("n"))));
            var odd = lam("n", ifThenElse(var("c"), var("n"), app(var("even"), var("n"))));
            var group = List.of(new PirTerm.Binding("even", even), new PirTerm.Binding("odd", odd));
            assertFalse(proven(new PirTerm.LetRec(group, app(var("even"), ONE))));
            assertFalse(proven(new PirTerm.LetRec(group, app(var("odd"), ONE))));
        }

        /** One reading per binding: two per nesting level would make this depth unreachable. */
        @Test void nestedRecursionIsReadOnce() {
            PirTerm term = TRUE;
            for (int depth = 0; depth < 200; depth++) {
                var name = "f" + depth;
                term = letRec(name, lam("x", ifThenElse(var("c"), term, app(var(name), var("x")))), app(var(name), ONE));
            }
            var nested = term;
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertTrue(proven(nested)));
        }

        @Test void aRepeatedNameHoldsWhatEveryBindingHolds() {
            var group = List.of(new PirTerm.Binding("f", lam("x", TRUE)), new PirTerm.Binding("f", lam("x", ONE)));
            assertFalse(proven(new PirTerm.LetRec(group, app(var("f"), ONE))));
        }
    }

    @Nested
    class Matches {
        @Test void integerCaseJoinsItsBranches() {
            assertTrue(proven(new PirTerm.IntegerCase(ZERO, List.of(TRUE, FALSE, ERROR))));
            assertFalse(proven(new PirTerm.IntegerCase(ZERO, List.of(TRUE, ONE))));
        }

        @Test void dataMatchJoinsItsBranches() {
            var both = new PirTerm.DataMatch(var("d"), List.of(
                    new PirTerm.MatchBranch("A", List.of(), TRUE),
                    new PirTerm.MatchBranch("B", List.of(), FALSE)));
            var one = new PirTerm.DataMatch(var("d"), List.of(
                    new PirTerm.MatchBranch("A", List.of(), TRUE),
                    new PirTerm.MatchBranch("B", List.of(), ONE)));
            assertTrue(proven(both));
            assertFalse(proven(one));
        }

        @Test void pairMatchOverUnConstrDataReadsItsBody() {
            assertTrue(proven(new PirTerm.PairMatch(unConstr(var("d")), PAIR, "t", "fs", TRUE)));
            assertFalse(proven(new PirTerm.PairMatch(unConstr(var("d")), PAIR, "t", "fs", ONE)));
        }

        @Test void pairMatchOverAnythingElseIsUnproven() {
            // Case given 0 or false selects the only branch without applying it, and yields its lambda.
            assertFalse(proven(new PirTerm.PairMatch(ZERO, PAIR, "t", "fs", TRUE)));
            assertFalse(proven(new PirTerm.PairMatch(var("p"), PAIR, "t", "fs", TRUE)));
            assertFalse(proven(new PirTerm.PairMatch(app(builtin(DefaultFun.UnIData), ZERO), PAIR, "t", "fs", TRUE)));
        }

        @Test void listMatchIsUnproven() {
            assertFalse(proven(new PirTerm.ListMatch(var("xs"), "h", "t", TRUE, TRUE)));
        }

        @Test void aConstructedValueIsData() {
            assertFalse(proven(new PirTerm.DataConstr(1, new PirType.RecordType("R", List.of()), List.of())));
        }
    }

    @Nested
    class Shadowing {
        @Test void aLambdaParameterHidesAProvenLet() {
            assertFalse(proven(let("x", TRUE, app(lam("x", var("x")), ONE))));
        }

        @Test void aLetHidesAProvenLet() {
            assertFalse(proven(let("x", TRUE, let("x", ONE, var("x")))));
            assertTrue(proven(let("x", ONE, let("x", TRUE, var("x")))));
        }

        @Test void aLetValueSeesTheOuterBinding() {
            assertTrue(proven(let("x", TRUE, let("x", var("x"), var("x")))));
            assertFalse(proven(let("x", ONE, let("x", var("x"), var("x")))));
        }

        @Test void aLetRecHidesAProvenLet() {
            assertFalse(proven(let("f", lam("x", TRUE), letRec("f", lam("x", ONE), app(var("f"), ONE)))));
            assertFalse(proven(let("x", TRUE, letRec("x", lam("y", ONE), var("x")))));
        }

        @Test void aLetRecNameIsBoundInItsOwnValue() {
            // The inner f is this binding, whose result is an integer; an outer proven f plays no part.
            var value = lam("x", ifThenElse(var("c"), ONE, app(var("f"), var("x"))));
            assertFalse(proven(let("f", lam("x", TRUE), letRec("f", value, app(var("f"), ONE)))));
        }

        @Test void aParameterHidesTheRecursiveName() {
            assertFalse(proven(letRec("f", lam("f", app(var("f"), ONE)), app(var("f"), ONE))));
        }

        @Test void aParameterHidesAKnownFunction() {
            assertFalse(proven(let("f", lam("x", TRUE), app(lam("f", app(var("f"), ONE)), ONE))));
        }

        @Test void aFunctionReadsTheScopeOfItsDefinitionNotOfItsCall() {
            assertTrue(proven(let("x", TRUE, let("f", lam("y", var("x")), let("x", ONE, app(var("f"), ZERO))))));
            assertFalse(proven(let("x", ONE, let("f", lam("y", var("x")), let("x", TRUE, app(var("f"), ZERO))))));
        }

        @Test void aPairMatchBinderHidesAProvenLet() {
            assertFalse(proven(let("t", TRUE, new PirTerm.PairMatch(unConstr(var("d")), PAIR, "t", "fs", var("t")))));
            assertFalse(proven(let("fs", TRUE, new PirTerm.PairMatch(unConstr(var("d")), PAIR, "t", "fs", var("fs")))));
        }

        @Test void aMatchFieldHidesAProvenLet() {
            var field = new PirTerm.MatchBranch("A", List.of("x"), List.of(BOOL), var("x"));
            assertFalse(proven(let("x", TRUE, new PirTerm.DataMatch(var("d"), List.of(field)))));
        }

        @Test void aPatternVariableHidesAProvenLet() {
            var pattern = new PirTerm.MatchBranch("A", List.of(), List.of(), var("x"), "x");
            assertFalse(proven(let("x", TRUE, new PirTerm.DataMatch(var("d"), List.of(pattern)))));
        }
    }

    @Nested
    class Lowering {
        private final CompilationContext safe = context(OptimizationLevel.PV11_SAFE);

        private static CompilationContext context(OptimizationLevel level) {
            return CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(level));
        }

        private static Term lower(PirTerm term, CompilationContext context) {
            return new UplcGenerator(context, null).generate(term);
        }

        private static Term body(Term lambda) {
            return assertInstanceOf(Term.Lam.class, lambda).body();
        }

        @Test void aProvenConditionLowersToCaseAndRecordsTheRule() {
            var term = lower(ifThenElse(TRUE, ONE, ZERO), safe);
            var match = assertInstanceOf(Term.Case.class, term);
            assertEquals(new Term.Const(Constant.integer(BigInteger.ZERO)), match.branches().get(0));
            assertEquals(new Term.Const(Constant.integer(BigInteger.ONE)), match.branches().get(1));
            assertTrue(safe.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_BOOL_RULE));
        }

        @Test void anUnprovenConditionLowersToTheBaselineShapeAndRecordsNoRule() {
            var unproven = lam("x", ifThenElse(var("x"), ONE, ZERO));
            var term = lower(unproven, safe);
            assertEquals(lower(unproven, context(OptimizationLevel.BASELINE)), term);
            assertInstanceOf(Term.Force.class, body(term));
            assertFalse(safe.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_BOOL_RULE));
        }

        @Test void aFieldDecodedByTheMatchExpansionIsProven() {
            var branch = new PirTerm.MatchBranch("A", List.of("flag"), List.of(BOOL),
                    ifThenElse(var("flag"), ONE, ZERO));
            var term = lower(lam("d", new PirTerm.DataMatch(new PirTerm.Var("d", DATA), List.of(branch))), safe);
            assertTrue(safe.optimizationReport().appliedRules().contains(UplcGenerator.PV11_CASE_BOOL_RULE));
            assertEquals(0, builtinCount(term));
        }

        @Test void aRawMatchFieldIsNotProven() {
            var branch = new PirTerm.MatchBranch("A", List.of("flag"), List.of(DATA),
                    ifThenElse(var("flag"), ONE, ZERO));
            var term = lower(lam("d", new PirTerm.DataMatch(new PirTerm.Var("d", DATA), List.of(branch))), safe);
            assertEquals(1, builtinCount(term));
        }

        @Test void theGeneratorSeesShadowingByALambda() {
            var term = lower(let("x", TRUE, lam("x", ifThenElse(var("x"), ONE, ZERO))), safe);
            assertEquals(1, builtinCount(term));
        }

        @Test void theGeneratorSeesShadowingByLetAndLetRec() {
            assertEquals(1, builtinCount(lower(lam("p",
                    let("x", TRUE, let("x", var("p"), ifThenElse(var("x"), ONE, ZERO)))), safe)));
            assertEquals(2, builtinCount(lower(lam("p", let("f", lam("x", TRUE),
                    letRec("f", lam("x", ifThenElse(var("p"), ONE, app(var("f"), var("x")))),
                            ifThenElse(app(var("f"), ONE), ONE, ZERO)))), safe)));
        }

        @Test void theRecursiveBindingHoldsItsFactInItsOwnBodyAndInItsScope() {
            var f = lam("x", ifThenElse(app(var("f"), var("x")), TRUE, FALSE));
            var term = lower(letRec("f", f, ifThenElse(app(var("f"), ONE), ONE, ZERO)), safe);
            assertEquals(0, builtinCount(term));
        }

        @Test void mutualRecursionLoweredThroughTheDecompositionIsProven() {
            var even = lam("n", ifThenElse(app(var("odd"), var("n")), TRUE, FALSE));
            var odd = lam("n", ifThenElse(app(var("even"), var("n")), FALSE, TRUE));
            var term = lower(new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("even", even), new PirTerm.Binding("odd", odd)),
                    ifThenElse(app(var("even"), ONE), ONE, ZERO)), safe);
            assertEquals(0, builtinCount(term));
        }

        private static int builtinCount(Term term) {
            return switch (term) {
                case Term.Var _, Term.Const _, Term.Error _ -> 0;
                case Term.Builtin(var fun) -> fun == DefaultFun.IfThenElse ? 1 : 0;
                case Term.Lam(_, var body) -> builtinCount(body);
                case Term.Delay(var body) -> builtinCount(body);
                case Term.Force(var body) -> builtinCount(body);
                case Term.Apply(var function, var argument) -> builtinCount(function) + builtinCount(argument);
                case Term.Constr(_, var fields) -> fields.stream().mapToInt(Lowering::builtinCount).sum();
                case Term.Case(var scrutinee, var branches) ->
                        builtinCount(scrutinee) + branches.stream().mapToInt(Lowering::builtinCount).sum();
            };
        }
    }
}
