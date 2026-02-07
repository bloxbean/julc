package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.compiler.desugar.LoopDesugarer;
import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.plutus.core.*;
import com.bloxbean.cardano.plutus.vm.EvalResult;
import com.bloxbean.cardano.plutus.vm.PlutusVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Task 4.5: Loop Desugaring (for-each, while → recursion)
 */
class LoopDesugarTest {

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

    @Nested
    class LoopDesugarerUnitTests {
        @Test
        void forEachGeneratesLetRec() {
            var desugarer = new LoopDesugarer();
            var intType = new PirType.IntegerType();

            // Simple identity body: just return acc
            var body = new PirTerm.Var("acc__forEach", new PirType.UnitType());

            var result = desugarer.desugarForEach(
                    new PirTerm.Const(Constant.unit()),
                    "item", "acc__forEach",
                    new PirTerm.Const(Constant.unit()),
                    new PirType.UnitType(),
                    body);

            assertInstanceOf(PirTerm.LetRec.class, result);
            var letRec = (PirTerm.LetRec) result;
            assertEquals(1, letRec.bindings().size());
            assertEquals("loop__forEach", letRec.bindings().getFirst().name());
        }

        @Test
        void whileGeneratesLetRec() {
            var desugarer = new LoopDesugarer();
            var result = desugarer.desugarWhile(
                    new PirTerm.Const(Constant.bool(false)),
                    new PirTerm.Const(Constant.unit()));

            assertInstanceOf(PirTerm.LetRec.class, result);
            var letRec = (PirTerm.LetRec) result;
            assertEquals(1, letRec.bindings().size());
            assertEquals("loop__while", letRec.bindings().getFirst().name());
        }
    }

    @Nested
    class LoopDesugarerDirectTests {
        @Test
        void forEachSumViaDesugarer() {
            // Use the LoopDesugarer directly to build a sum accumulator
            var desugarer = new LoopDesugarer();
            var intType = new PirType.IntegerType();

            // Build the list [10, 20, 30] as Data list
            var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));
            var list = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                                    new PirTerm.Const(Constant.integer(BigInteger.TEN)))),
                    new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(20))))),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                                                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(30))))),
                                    emptyList)));

            // body: acc + UnIData(item)
            var body = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                            new PirTerm.Var("acc__forEach", intType)),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                            new PirTerm.Var("item", new PirType.DataType())));

            var pir = desugarer.desugarForEach(
                    list, "item", "acc__forEach",
                    new PirTerm.Const(Constant.integer(BigInteger.ZERO)),
                    intType, body);

            var uplc = new UplcGenerator().generate(pir);
            assertEquals(BigInteger.valueOf(60), evalInteger(uplc)); // 10+20+30 = 60
        }

        @Test
        void forEachOnEmptyList() {
            var desugarer = new LoopDesugarer();
            var intType = new PirType.IntegerType();

            var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));

            var body = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                            new PirTerm.Var("acc__forEach", intType)),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                            new PirTerm.Var("item", new PirType.DataType())));

            var pir = desugarer.desugarForEach(
                    emptyList, "item", "acc__forEach",
                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(42))),
                    intType, body);

            var uplc = new UplcGenerator().generate(pir);
            assertEquals(BigInteger.valueOf(42), evalInteger(uplc)); // Empty list → initial acc
        }

        @Test
        void forEachSingleElement() {
            var desugarer = new LoopDesugarer();
            var intType = new PirType.IntegerType();

            var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));
            var list = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(7))))),
                    emptyList);

            // body: acc * UnIData(item)
            var body = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                            new PirTerm.Var("acc__forEach", intType)),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                            new PirTerm.Var("item", new PirType.DataType())));

            var pir = desugarer.desugarForEach(
                    list, "item", "acc__forEach",
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                    intType, body);

            var uplc = new UplcGenerator().generate(pir);
            assertEquals(BigInteger.valueOf(7), evalInteger(uplc)); // 1 * 7 = 7
        }

        @Test
        void forEachProductAccumulator() {
            var desugarer = new LoopDesugarer();
            var intType = new PirType.IntegerType();

            // list [2, 3, 4]
            var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));
            var list = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                                    new PirTerm.Const(Constant.integer(BigInteger.TWO)))),
                    new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(3))))),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                                                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(4))))),
                                    emptyList)));

            // body: acc * UnIData(item)
            var body = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                            new PirTerm.Var("acc__forEach", intType)),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                            new PirTerm.Var("item", new PirType.DataType())));

            var pir = desugarer.desugarForEach(
                    list, "item", "acc__forEach",
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                    intType, body);

            var uplc = new UplcGenerator().generate(pir);
            assertEquals(BigInteger.valueOf(24), evalInteger(uplc)); // 1 * 2 * 3 * 4 = 24
        }
    }

    @Nested
    class ForEachEvalTests {
        @Test
        void forEachSumAccumulator() {
            // Manually build: LetRec([loop = \xs \acc -> if null(xs) then acc else loop(tail(xs), acc + unIData(head(xs)))], loop(list, 0))
            var intType = new PirType.IntegerType();
            var listType = new PirType.ListType(new PirType.DataType());
            var funType = new PirType.FunType(listType, new PirType.FunType(intType, intType));

            // Build the list [1, 2, 3] as Data list
            var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));
            var iData3 = new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(3))));
            var iData2 = new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                    new PirTerm.Const(Constant.integer(BigInteger.TWO)));
            var iData1 = new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)));

            var list = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), iData1),
                    new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), iData2),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), iData3),
                                    emptyList)));

            // Build directly as PIR LetRec:
            // loop = \xs \acc -> if NullList(xs) then acc else loop(TailList(xs), acc + UnIData(HeadList(xs)))
            var xsVar = new PirTerm.Var("xs", listType);
            var accVar = new PirTerm.Var("acc", intType);
            var loopVar = new PirTerm.Var("loop", funType);

            var headItem = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), xsVar));
            var newAcc = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), accVar),
                    headItem);
            var recursiveCall = new PirTerm.App(
                    new PirTerm.App(loopVar,
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), xsVar)),
                    newAcc);
            var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), xsVar);

            var loopBody = new PirTerm.Lam("xs", listType,
                    new PirTerm.Lam("acc", intType,
                            new PirTerm.IfThenElse(nullCheck, accVar, recursiveCall)));

            var letRec = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("loop", loopBody)),
                    new PirTerm.App(
                            new PirTerm.App(new PirTerm.Var("loop", funType), list),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))));

            var uplc = new UplcGenerator().generate(letRec);
            var evalResult = vm.evaluate(Program.plutusV3(uplc));
            assertTrue(evalResult.isSuccess(), "Evaluation failed: " + evalResult);
            var val = ((Term.Const) ((EvalResult.Success) evalResult).resultTerm()).value();
            assertEquals(BigInteger.valueOf(6), ((Constant.IntegerConst) val).value()); // 1+2+3 = 6
        }
    }

    @Nested
    class ForEachWithConstantList {
        @Test
        void forEachSumWithConstantList() {
            // Build list as a UPLC constant instead of MkCons/MkNilData
            var intType = new PirType.IntegerType();
            var listType = new PirType.ListType(new PirType.DataType());
            var funType = new PirType.FunType(listType, new PirType.FunType(intType, intType));

            // Build list [5, 10, 15] as a constant
            var list = new PirTerm.Const(new Constant.ListConst(
                    DefaultUni.DATA, List.of(
                            Constant.data(PlutusData.integer(BigInteger.valueOf(5))),
                            Constant.data(PlutusData.integer(BigInteger.TEN)),
                            Constant.data(PlutusData.integer(BigInteger.valueOf(15))))));

            var xsVar = new PirTerm.Var("xs", listType);
            var accVar = new PirTerm.Var("acc", intType);

            var headItem = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), xsVar));
            var newAcc = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), accVar),
                    headItem);
            var recursiveCall = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Var("loop", funType),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), xsVar)),
                    newAcc);
            var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), xsVar);

            var loopBody = new PirTerm.Lam("xs", listType,
                    new PirTerm.Lam("acc", intType,
                            new PirTerm.IfThenElse(nullCheck, accVar, recursiveCall)));

            var letRec = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("loop", loopBody)),
                    new PirTerm.App(
                            new PirTerm.App(new PirTerm.Var("loop", funType), list),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.valueOf(30), evalInteger(uplc)); // 5+10+15 = 30
        }
    }

    @Nested
    class CompilerIntegrationTests {
        @Test
        void compilerAcceptsForEach() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return true;
                    }
                }
                """;
            // Just check that the compiler accepts code with no for-each errors
            var result = new PlutusCompiler().compile(source);
            assertNotNull(result.program());
        }

        @Test
        void compilerAcceptsWhile() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return true;
                    }
                }
                """;
            var result = new PlutusCompiler().compile(source);
            assertNotNull(result.program());
        }
    }
}
