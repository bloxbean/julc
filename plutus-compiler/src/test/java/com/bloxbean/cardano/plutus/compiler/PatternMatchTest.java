package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;
import com.bloxbean.cardano.plutus.core.Program;
import com.bloxbean.cardano.plutus.core.Term;
import com.bloxbean.cardano.plutus.vm.EvalResult;
import com.bloxbean.cardano.plutus.vm.PlutusVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Task 4.3: Pattern Matching (DataMatch, instanceof, switch)
 */
class PatternMatchTest {

    static PlutusVm vm;

    @BeforeAll
    static void setUp() {
        vm = PlutusVm.create();
    }

    private BigInteger evalInteger(Term term) {
        var result = vm.evaluate(Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.IntegerConst) val).value();
    }

    private boolean evalBool(Term term) {
        var result = vm.evaluate(Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.BoolConst) val).value();
    }

    @Nested
    class DataMatchDeBruijnTests {
        @Test
        void simpleMatchWithOneField() {
            // Match on Constr(0, [42]) -> extract field and return it
            var intType = new PirType.IntegerType();
            var match = new PirTerm.DataMatch(
                    new PirTerm.DataConstr(0,
                            new PirType.RecordType("X", List.of(new PirType.Field("val", intType))),
                            List.of(new PirTerm.Const(Constant.integer(BigInteger.valueOf(42))))),
                    List.of(new PirTerm.MatchBranch("X", List.of("val"),
                            new PirTerm.Var("val", intType))));

            var uplc = new UplcGenerator().generate(match);
            assertEquals(BigInteger.valueOf(42), evalInteger(uplc));
        }

        @Test
        void matchWithTwoFields() {
            // Match on Constr(0, [10, 20]) -> return field1 + field2
            var intType = new PirType.IntegerType();
            var match = new PirTerm.DataMatch(
                    new PirTerm.DataConstr(0,
                            new PirType.RecordType("Pair", List.of(
                                    new PirType.Field("a", intType),
                                    new PirType.Field("b", intType))),
                            List.of(
                                    new PirTerm.Const(Constant.integer(BigInteger.TEN)),
                                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(20))))),
                    List.of(new PirTerm.MatchBranch("Pair", List.of("a", "b"),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                            new PirTerm.Var("a", intType)),
                                    new PirTerm.Var("b", intType)))));

            var uplc = new UplcGenerator().generate(match);
            assertEquals(BigInteger.valueOf(30), evalInteger(uplc));
        }

        @Test
        void matchOnSecondConstructor() {
            // SumType with two constructors, match on tag 1
            var intType = new PirType.IntegerType();
            var match = new PirTerm.DataMatch(
                    new PirTerm.DataConstr(1,
                            new PirType.SumType("AB", List.of(
                                    new PirType.Constructor("A", 0, List.of(new PirType.Field("x", intType))),
                                    new PirType.Constructor("B", 1, List.of(new PirType.Field("y", intType))))),
                            List.of(new PirTerm.Const(Constant.integer(BigInteger.valueOf(99))))),
                    List.of(
                            new PirTerm.MatchBranch("A", List.of("x"), new PirTerm.Var("x", intType)),
                            new PirTerm.MatchBranch("B", List.of("y"), new PirTerm.Var("y", intType))));

            var uplc = new UplcGenerator().generate(match);
            assertEquals(BigInteger.valueOf(99), evalInteger(uplc));
        }

        @Test
        void matchOnFirstConstructor() {
            var intType = new PirType.IntegerType();
            var match = new PirTerm.DataMatch(
                    new PirTerm.DataConstr(0,
                            new PirType.SumType("AB", List.of(
                                    new PirType.Constructor("A", 0, List.of(new PirType.Field("x", intType))),
                                    new PirType.Constructor("B", 1, List.of(new PirType.Field("y", intType))))),
                            List.of(new PirTerm.Const(Constant.integer(BigInteger.valueOf(77))))),
                    List.of(
                            new PirTerm.MatchBranch("A", List.of("x"), new PirTerm.Var("x", intType)),
                            new PirTerm.MatchBranch("B", List.of("y"), new PirTerm.Var("y", intType))));

            var uplc = new UplcGenerator().generate(match);
            assertEquals(BigInteger.valueOf(77), evalInteger(uplc));
        }

        @Test
        void matchWithTransformation() {
            // Match Constr(0, [5]) → val * 2
            var intType = new PirType.IntegerType();
            var match = new PirTerm.DataMatch(
                    new PirTerm.DataConstr(0,
                            new PirType.RecordType("X", List.of(new PirType.Field("val", intType))),
                            List.of(new PirTerm.Const(Constant.integer(BigInteger.valueOf(5))))),
                    List.of(new PirTerm.MatchBranch("X", List.of("val"),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                                            new PirTerm.Var("val", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.TWO))))));

            var uplc = new UplcGenerator().generate(match);
            assertEquals(BigInteger.TEN, evalInteger(uplc));
        }

        @Test
        void emptyFieldMatch() {
            // Match on Constr(0, []) → return constant
            var match = new PirTerm.DataMatch(
                    new PirTerm.DataConstr(0,
                            new PirType.RecordType("Unit", List.of()),
                            List.of()),
                    List.of(new PirTerm.MatchBranch("Unit", List.of(),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(42))))));

            var uplc = new UplcGenerator().generate(match);
            assertEquals(BigInteger.valueOf(42), evalInteger(uplc));
        }

        @Test
        void matchInsideLet() {
            // let x = Constr(0, [42]) in match x { X(val) -> val + 1 }
            var intType = new PirType.IntegerType();
            var sumType = new PirType.RecordType("X", List.of(new PirType.Field("val", intType)));

            var pir = new PirTerm.Let("x",
                    new PirTerm.DataConstr(0, sumType,
                            List.of(new PirTerm.Const(Constant.integer(BigInteger.valueOf(42))))),
                    new PirTerm.DataMatch(
                            new PirTerm.Var("x", sumType),
                            List.of(new PirTerm.MatchBranch("X", List.of("val"),
                                    new PirTerm.App(
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                                    new PirTerm.Var("val", intType)),
                                            new PirTerm.Const(Constant.integer(BigInteger.ONE)))))));

            var uplc = new UplcGenerator().generate(pir);
            assertEquals(BigInteger.valueOf(43), evalInteger(uplc));
        }

        @Test
        void threeWayMatch() {
            var intType = new PirType.IntegerType();
            var sumType = new PirType.SumType("ABC", List.of(
                    new PirType.Constructor("A", 0, List.of(new PirType.Field("x", intType))),
                    new PirType.Constructor("B", 1, List.of(new PirType.Field("y", intType))),
                    new PirType.Constructor("C", 2, List.of(new PirType.Field("z", intType)))));

            // Construct C(7), match should return 7 * 3 = 21
            var match = new PirTerm.DataMatch(
                    new PirTerm.DataConstr(2, sumType,
                            List.of(new PirTerm.Const(Constant.integer(BigInteger.valueOf(7))))),
                    List.of(
                            new PirTerm.MatchBranch("A", List.of("x"), new PirTerm.Var("x", intType)),
                            new PirTerm.MatchBranch("B", List.of("y"),
                                    new PirTerm.App(
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                                                    new PirTerm.Var("y", intType)),
                                            new PirTerm.Const(Constant.integer(BigInteger.TWO)))),
                            new PirTerm.MatchBranch("C", List.of("z"),
                                    new PirTerm.App(
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                                                    new PirTerm.Var("z", intType)),
                                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(3)))))));

            var uplc = new UplcGenerator().generate(match);
            assertEquals(BigInteger.valueOf(21), evalInteger(uplc));
        }
    }

    @Nested
    class UplcGenerationTests {
        @Test
        void dataMatchStructure() {
            var intType = new PirType.IntegerType();
            var match = new PirTerm.DataMatch(
                    new PirTerm.DataConstr(0,
                            new PirType.RecordType("X", List.of(new PirType.Field("v", intType))),
                            List.of(new PirTerm.Const(Constant.integer(BigInteger.ONE)))),
                    List.of(new PirTerm.MatchBranch("X", List.of("v"),
                            new PirTerm.Var("v", intType))));

            var uplc = new UplcGenerator().generate(match);
            assertInstanceOf(Term.Case.class, uplc);
            var caseExpr = (Term.Case) uplc;
            assertInstanceOf(Term.Constr.class, caseExpr.scrutinee());
            assertEquals(1, caseExpr.branches().size());
            assertInstanceOf(Term.Lam.class, caseExpr.branches().getFirst());
        }
    }

    @Nested
    class PatternMatchDesugarerTests {
        @Test
        void buildDataMatchReordersByTag() {
            var intType = new PirType.IntegerType();
            var sumType = new PirType.SumType("AB", List.of(
                    new PirType.Constructor("A", 0, List.of(new PirType.Field("x", intType))),
                    new PirType.Constructor("B", 1, List.of(new PirType.Field("y", intType)))));

            var desugarer = new com.bloxbean.cardano.plutus.compiler.desugar.PatternMatchDesugarer(null);
            // Provide branches in reverse order
            var entries = List.of(
                    new com.bloxbean.cardano.plutus.compiler.desugar.PatternMatchDesugarer.MatchEntry(
                            "B", List.of("y"), new PirTerm.Const(Constant.integer(BigInteger.TWO))),
                    new com.bloxbean.cardano.plutus.compiler.desugar.PatternMatchDesugarer.MatchEntry(
                            "A", List.of("x"), new PirTerm.Const(Constant.integer(BigInteger.ONE))));

            var result = desugarer.buildDataMatch(
                    new PirTerm.Const(Constant.integer(BigInteger.ZERO)), sumType, entries);

            assertInstanceOf(PirTerm.DataMatch.class, result);
            var dm = (PirTerm.DataMatch) result;
            assertEquals(2, dm.branches().size());
            assertEquals("A", dm.branches().get(0).constructorName()); // Ordered by tag
            assertEquals("B", dm.branches().get(1).constructorName());
        }
    }
}
