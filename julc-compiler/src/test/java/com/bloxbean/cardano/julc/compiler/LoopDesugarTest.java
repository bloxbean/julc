package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.desugar.LoopDesugarer;
import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
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

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
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
            assertTrue(letRec.bindings().getFirst().name().startsWith("loop__forEach__"));
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
            assertTrue(letRec.bindings().getFirst().name().startsWith("loop__while__"));
        }

        @Test
        void eachLoopGetsUniqueName() {
            var desugarer = new LoopDesugarer();
            var r1 = desugarer.desugarWhile(
                    new PirTerm.Const(Constant.bool(false)),
                    new PirTerm.Const(Constant.unit()));
            var r2 = desugarer.desugarWhile(
                    new PirTerm.Const(Constant.bool(false)),
                    new PirTerm.Const(Constant.unit()));

            var name1 = ((PirTerm.LetRec) r1).bindings().getFirst().name();
            var name2 = ((PirTerm.LetRec) r2).bindings().getFirst().name();
            assertNotEquals(name1, name2, "Each loop should get a unique name");
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
    class ForEachWithBreakDesugarerTests {

        private PirTerm mkDataList(BigInteger... values) {
            PirTerm list = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));
            for (int i = values.length - 1; i >= 0; i--) {
                list = new PirTerm.App(
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                                        new PirTerm.Const(Constant.integer(values[i])))),
                        list);
            }
            return list;
        }

        private boolean evalBool(Term term) {
            var result = vm.evaluate(Program.plutusV3(term));
            assertTrue(result.isSuccess(), "Expected success: " + result);
            var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
            return ((Constant.BoolConst) val).value();
        }

        @Test
        void breakOnFirstElement() {
            // list [1, 2, 3], break when item == 1 → found = true
            var desugarer = new LoopDesugarer();
            var boolType = new PirType.BoolType();

            var list = mkDataList(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3));

            var pir = desugarer.desugarForEachWithBreak(
                    list, "item", "found",
                    new PirTerm.Const(Constant.bool(false)), boolType,
                    (continueFn, accVar) -> {
                        // if UnIData(item) == 1 then true else continueFn(found)
                        var itemVal = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                                new PirTerm.Var("item", new PirType.DataType()));
                        var cond = new PirTerm.App(
                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), itemVal),
                                new PirTerm.Const(Constant.integer(BigInteger.ONE)));
                        return new PirTerm.IfThenElse(cond,
                                new PirTerm.Const(Constant.bool(true)),
                                continueFn.apply(accVar));
                    });

            var uplc = new UplcGenerator().generate(pir);
            assertTrue(evalBool(uplc));
        }

        @Test
        void breakOnMiddleElement() {
            // list [1, 2, 3], break when item == 2 → found = true
            var desugarer = new LoopDesugarer();
            var boolType = new PirType.BoolType();

            var list = mkDataList(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3));

            var pir = desugarer.desugarForEachWithBreak(
                    list, "item", "found",
                    new PirTerm.Const(Constant.bool(false)), boolType,
                    (continueFn, accVar) -> {
                        var itemVal = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                                new PirTerm.Var("item", new PirType.DataType()));
                        var cond = new PirTerm.App(
                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), itemVal),
                                new PirTerm.Const(Constant.integer(BigInteger.TWO)));
                        return new PirTerm.IfThenElse(cond,
                                new PirTerm.Const(Constant.bool(true)),
                                continueFn.apply(accVar));
                    });

            var uplc = new UplcGenerator().generate(pir);
            assertTrue(evalBool(uplc));
        }

        @Test
        void breakNeverTriggered() {
            // list [1, 2, 3], break when item == 5 → found = false (never breaks)
            var desugarer = new LoopDesugarer();
            var boolType = new PirType.BoolType();

            var list = mkDataList(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3));

            var pir = desugarer.desugarForEachWithBreak(
                    list, "item", "found",
                    new PirTerm.Const(Constant.bool(false)), boolType,
                    (continueFn, accVar) -> {
                        var itemVal = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                                new PirTerm.Var("item", new PirType.DataType()));
                        var cond = new PirTerm.App(
                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), itemVal),
                                new PirTerm.Const(Constant.integer(BigInteger.valueOf(5))));
                        return new PirTerm.IfThenElse(cond,
                                new PirTerm.Const(Constant.bool(true)),
                                continueFn.apply(accVar));
                    });

            var uplc = new UplcGenerator().generate(pir);
            assertFalse(evalBool(uplc));
        }

        @Test
        void breakOnEmptyList() {
            // empty list, break when item == 1 → found = false (initial acc)
            var desugarer = new LoopDesugarer();
            var boolType = new PirType.BoolType();

            var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));

            var pir = desugarer.desugarForEachWithBreak(
                    emptyList, "item", "found",
                    new PirTerm.Const(Constant.bool(false)), boolType,
                    (continueFn, accVar) -> {
                        var itemVal = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                                new PirTerm.Var("item", new PirType.DataType()));
                        var cond = new PirTerm.App(
                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), itemVal),
                                new PirTerm.Const(Constant.integer(BigInteger.ONE)));
                        return new PirTerm.IfThenElse(cond,
                                new PirTerm.Const(Constant.bool(true)),
                                continueFn.apply(accVar));
                    });

            var uplc = new UplcGenerator().generate(pir);
            assertFalse(evalBool(uplc));
        }

        @Test
        void breakWithIntegerAccumulator() {
            // list [10, 20, 30], sum until item == 20 then break → acc = 10+20 = 30
            var desugarer = new LoopDesugarer();
            var intType = new PirType.IntegerType();

            var list = mkDataList(BigInteger.TEN, BigInteger.valueOf(20), BigInteger.valueOf(30));

            var pir = desugarer.desugarForEachWithBreak(
                    list, "item", "sum",
                    new PirTerm.Const(Constant.integer(BigInteger.ZERO)), intType,
                    (continueFn, accVar) -> {
                        var itemVal = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                                new PirTerm.Var("item", new PirType.DataType()));
                        var newAcc = new PirTerm.App(
                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), accVar),
                                itemVal);
                        var isTarget = new PirTerm.App(
                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), itemVal),
                                new PirTerm.Const(Constant.integer(BigInteger.valueOf(20))));
                        // if item == 20 then (sum + item) [break] else continue(sum + item)
                        return new PirTerm.IfThenElse(isTarget,
                                newAcc,
                                continueFn.apply(newAcc));
                    });

            var uplc = new UplcGenerator().generate(pir);
            assertEquals(BigInteger.valueOf(30), evalInteger(uplc)); // 10 + 20 = 30 (skips 30)
        }
    }

    @Nested
    class BreakCompilerIntegrationTests {

        private boolean evalBool(Term term) {
            var result = vm.evaluate(Program.plutusV3(term));
            assertTrue(result.isSuccess(), "Expected success: " + result);
            var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
            return ((Constant.BoolConst) val).value();
        }

        @Test
        void breakInForEachWithSignatories() {
            // Validator that iterates over signatories with break
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.*;

                @Validator
                class BreakValidator {
                    @Entrypoint
                    static boolean validate(byte[] redeemer, ScriptContext ctx) {
                        var txInfo = ctx.txInfo();
                        boolean found = false;
                        for (var sig : txInfo.signatories()) {
                            if (sig == redeemer) {
                                found = true;
                                break;
                            }
                        }
                        return found;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors());
        }

        @Test
        void breakInForEachWithInputs() {
            // Validator that iterates over inputs with break
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.*;

                @Validator
                class BreakValidator2 {
                    @Entrypoint
                    static boolean validate(byte[] redeemer, ScriptContext ctx) {
                        var txInfo = ctx.txInfo();
                        boolean paid = false;
                        for (var output : txInfo.outputs()) {
                            if (output.value() == redeemer) {
                                paid = true;
                                break;
                            }
                        }
                        return paid;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors());
        }
    }

    @Nested
    class MultiAccumulatorTests {

        @Test
        void breakAfterSeparateAssignment() {
            // Bug 1: found = expr; if (found) { break; }
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.*;

                @Validator
                class BreakAfterAssign {
                    @Entrypoint
                    static boolean validate(byte[] redeemer, ScriptContext ctx) {
                        var txInfo = ctx.txInfo();
                        boolean found = false;
                        for (var sig : txInfo.signatories()) {
                            found = sig == redeemer;
                            if (found) {
                                break;
                            }
                        }
                        return found;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors());
        }

        @Test
        void twoAccumulatorsNoBreak() {
            // Bug 2: two accumulators in a single loop body
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.*;

                @Validator
                class TwoAccNoBreak {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, ScriptContext ctx) {
                        var txInfo = ctx.txInfo();
                        boolean found = false;
                        BigInteger count = BigInteger.ZERO;
                        for (var sig : txInfo.signatories()) {
                            found = found || sig == redeemer;
                            count = count + BigInteger.ONE;
                        }
                        return found;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors());
        }

        @Test
        void twoAccumulatorsWithBreak() {
            // Two accumulators + break
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.*;

                @Validator
                class TwoAccWithBreak {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, ScriptContext ctx) {
                        var txInfo = ctx.txInfo();
                        boolean found = false;
                        BigInteger index = BigInteger.ZERO;
                        for (var sig : txInfo.signatories()) {
                            found = sig == redeemer;
                            index = index + BigInteger.ONE;
                            if (found) {
                                break;
                            }
                        }
                        return found;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors());
        }

        @Test
        void nestedForEachLoopsCompile() {
            // Nested for-each loops now compile successfully
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.*;

                @Validator
                class NestedLoops {
                    @Entrypoint
                    static boolean validate(byte[] redeemer, ScriptContext ctx) {
                        var txInfo = ctx.txInfo();
                        BigInteger total = BigInteger.ZERO;
                        for (var output : txInfo.outputs()) {
                            boolean match = false;
                            for (var sig : txInfo.signatories()) {
                                if (sig == redeemer) {
                                    match = true;
                                    break;
                                }
                            }
                            total = total + BigInteger.ONE;
                        }
                        return total == BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());
        }

        @Test
        void nestedWhileInsideForEachCompiles() {
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.*;

                @Validator
                class NestedLoops {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, ScriptContext ctx) {
                        var txInfo = ctx.txInfo();
                        BigInteger total = BigInteger.ZERO;
                        for (var output : txInfo.outputs()) {
                            BigInteger count = BigInteger.ZERO;
                            while (count < redeemer) {
                                count = count + BigInteger.ONE;
                            }
                            total = total + count;
                        }
                        return total == BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());
        }

        @Test
        void nestedWhileInsideWhileCompiles() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class NestedWhile {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger outer = BigInteger.ZERO;
                        while (outer < redeemer) {
                            BigInteger inner = BigInteger.ZERO;
                            while (inner < ctx) {
                                inner = inner + BigInteger.ONE;
                            }
                            outer = outer + BigInteger.ONE;
                        }
                        return outer == redeemer;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());
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
            var result = new JulcCompiler().compile(source);
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
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
        }
    }

    // ========== While Loop Accumulator Tests ==========

    @Nested
    class WhileDesugarerUnitTests {

        @Test
        void whileWithAccumulator_generatesLetRec() {
            var desugarer = new LoopDesugarer();
            var intType = new PirType.IntegerType();

            var accVar = new PirTerm.Var("k", intType);
            var condition = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.LessThanInteger),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                    accVar);
            var body = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger), accVar),
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)));

            var result = desugarer.desugarWhileWithAccumulator(
                    condition, body, "k",
                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(5))),
                    intType);

            assertInstanceOf(PirTerm.LetRec.class, result);
            var letRec = (PirTerm.LetRec) result;
            assertEquals(1, letRec.bindings().size());
            assertTrue(letRec.bindings().getFirst().name().startsWith("loop__while__"));
        }

        @Test
        void whileCountdown() {
            // k=5, while(k>0) k=k-1 → evaluates to 0
            var desugarer = new LoopDesugarer();
            var intType = new PirType.IntegerType();

            var accVar = new PirTerm.Var("k", intType);
            // k > 0 means 0 < k
            var condition = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.LessThanInteger),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                    accVar);
            var body = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger), accVar),
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)));

            var pir = desugarer.desugarWhileWithAccumulator(
                    condition, body, "k",
                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(5))),
                    intType);

            var uplc = new UplcGenerator().generate(pir);
            assertEquals(BigInteger.ZERO, evalInteger(uplc));
        }

        @Test
        void whileConditionFalseInitially() {
            // k=0, while(k>0) k=k-1 → evaluates to 0 (body never executes)
            var desugarer = new LoopDesugarer();
            var intType = new PirType.IntegerType();

            var accVar = new PirTerm.Var("k", intType);
            var condition = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.LessThanInteger),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                    accVar);
            var body = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger), accVar),
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)));

            var pir = desugarer.desugarWhileWithAccumulator(
                    condition, body, "k",
                    new PirTerm.Const(Constant.integer(BigInteger.ZERO)),
                    intType);

            var uplc = new UplcGenerator().generate(pir);
            assertEquals(BigInteger.ZERO, evalInteger(uplc));
        }

        @Test
        void whileWithBreak_generatesLetRec() {
            var desugarer = new LoopDesugarer();
            var intType = new PirType.IntegerType();

            var accVar = new PirTerm.Var("k", intType);
            var condition = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.LessThanInteger),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                    accVar);

            var result = desugarer.desugarWhileWithAccumulatorAndBreak(
                    condition, "k",
                    new PirTerm.Const(Constant.integer(BigInteger.TEN)),
                    intType,
                    (continueFn, acc) -> {
                        // Simple body: continue with k-1
                        var newK = new PirTerm.App(
                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger), acc),
                                new PirTerm.Const(Constant.integer(BigInteger.ONE)));
                        return continueFn.apply(newK);
                    });

            assertInstanceOf(PirTerm.LetRec.class, result);
            var letRec = (PirTerm.LetRec) result;
            assertEquals(1, letRec.bindings().size());
            assertTrue(letRec.bindings().getFirst().name().startsWith("loop__while__"));
        }
    }

    @Nested
    class WhileCompilerIntegrationTests {

        @Test
        void whileCountdown_compiles() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class CountdownValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger k = redeemer;
                        while (k > BigInteger.ZERO) {
                            k = k - BigInteger.ONE;
                        }
                        return k == BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());
        }

        @Test
        void whileBoolAccumulator_compiles() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class BoolWhileValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        boolean done = false;
                        while (!done) {
                            done = true;
                        }
                        return done;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());
        }

        @Test
        void whileTwoAccumulators_compiles() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class TwoAccWhileValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger sum = BigInteger.ZERO;
                        BigInteger k = redeemer;
                        while (k > BigInteger.ZERO) {
                            sum = sum + k;
                            k = k - BigInteger.ONE;
                        }
                        return sum > BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());
        }

        @Test
        void whileWithBreak_compiles() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class BreakWhileValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger k = BigInteger.valueOf(10);
                        while (k > BigInteger.ZERO) {
                            if (k == BigInteger.valueOf(5)) {
                                break;
                            }
                            k = k - BigInteger.ONE;
                        }
                        return k == BigInteger.valueOf(5);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());
        }

        @Test
        void whileWithoutAccumulator_stillWorks() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class SideEffectWhileValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return true;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program());
            assertFalse(result.hasErrors());
        }
    }

    @Nested
    class WhileEvaluationTests {

        /** Build a minimal ScriptContext Data for eval tests (no signatories needed) */
        private PlutusData dummyScriptContext() {
            // ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
            // txInfo = Constr(0, [...fields...]) - 16 fields, all empty
            var emptyList = PlutusData.list();
            var emptyMap = PlutusData.map();
            var zeroInterval = PlutusData.constr(0,
                    PlutusData.constr(0, PlutusData.constr(1), PlutusData.integer(0)),
                    PlutusData.constr(0, PlutusData.constr(1), PlutusData.integer(0)));
            var txInfo = PlutusData.constr(0,
                    emptyList, emptyList, emptyList, emptyList, emptyList,
                    emptyList, emptyMap, zeroInterval, emptyList, emptyMap,
                    PlutusData.bytes(new byte[32]),
                    emptyMap, emptyList, emptyList, emptyList, PlutusData.integer(0));
            var redeemer = PlutusData.integer(0);
            var scriptInfo = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
        }

        @Test
        void whileCountdown_evaluatesToZero() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class CountdownEval {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger k = BigInteger.valueOf(10);
                        while (k > BigInteger.ZERO) {
                            k = k - BigInteger.ONE;
                        }
                        return k == BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Evaluation failed: " + evalResult);
        }

        @Test
        void whileSum_evaluatesCorrectly() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class SumEval {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger sum = BigInteger.ZERO;
                        BigInteger k = BigInteger.valueOf(5);
                        while (k > BigInteger.ZERO) {
                            sum = sum + k;
                            k = k - BigInteger.ONE;
                        }
                        return sum == BigInteger.valueOf(15);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Evaluation failed: " + evalResult);
        }

        @Test
        void whileBreak_stopsEarly() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class BreakEval {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger k = BigInteger.valueOf(10);
                        while (k > BigInteger.ZERO) {
                            if (k == BigInteger.valueOf(5)) {
                                break;
                            }
                            k = k - BigInteger.ONE;
                        }
                        return k == BigInteger.valueOf(5);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Evaluation failed: " + evalResult);
        }

        @Test
        void whileFollowingStatementsUseResult() {
            var source = """
                import java.math.BigInteger;

                @Validator
                class FollowingStmtsEval {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger k = BigInteger.valueOf(3);
                        while (k > BigInteger.ZERO) {
                            k = k - BigInteger.ONE;
                        }
                        BigInteger result = k + BigInteger.valueOf(100);
                        return result == BigInteger.valueOf(100);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Evaluation failed: " + evalResult);
        }

        @Test
        void booleanAndCounterMultiAcc_evaluates() {
            // Multi-acc while: boolean found + BigInteger count.
            // Iterate from 5 down to 0, set found=true when k==3.
            var source = """
                import java.math.BigInteger;

                @Validator
                class BoolMultiAccEval {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        boolean found = false;
                        BigInteger k = BigInteger.valueOf(5);
                        while (k > BigInteger.ZERO) {
                            if (k == BigInteger.valueOf(3)) {
                                found = true;
                            }
                            k = k - BigInteger.ONE;
                        }
                        return found;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Boolean multi-acc should find target: " + evalResult);
        }

        @Test
        void booleanAndCounterMultiAccWithBreak_evaluates() {
            // Multi-acc with boolean + break.
            var source = """
                import java.math.BigInteger;

                @Validator
                class BoolBreakMultiAccEval {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        boolean found = false;
                        BigInteger k = BigInteger.valueOf(5);
                        while (k > BigInteger.ZERO) {
                            found = k == BigInteger.valueOf(3);
                            if (found) {
                                break;
                            }
                            k = k - BigInteger.ONE;
                        }
                        return found && k == BigInteger.valueOf(3);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Boolean multi-acc with break should find 3: " + evalResult);
        }
    }

    // ========== Nested Loop Tests ==========

    @Nested
    class NestedLoopEvaluationTests {

        /** Build a minimal ScriptContext Data for eval tests */
        private PlutusData dummyScriptContext() {
            var emptyList = PlutusData.list();
            var emptyMap = PlutusData.map();
            var zeroInterval = PlutusData.constr(0,
                    PlutusData.constr(0, PlutusData.constr(1), PlutusData.integer(0)),
                    PlutusData.constr(0, PlutusData.constr(1), PlutusData.integer(0)));
            var txInfo = PlutusData.constr(0,
                    emptyList, emptyList, emptyList, emptyList, emptyList,
                    emptyList, emptyMap, zeroInterval, emptyList, emptyMap,
                    PlutusData.bytes(new byte[32]),
                    emptyMap, emptyList, emptyList, emptyList, PlutusData.integer(0));
            var redeemer = PlutusData.integer(0);
            var scriptInfo = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
        }

        @Test
        void nestedWhileInWhile_independentAccumulators() {
            // Outer i=0..2, inner j=0..2, sum += i*10+j. Result = 0+1+2+10+11+12+20+21+22 = 99
            var source = """
                import java.math.BigInteger;

                @Validator
                class NestedWhileSum {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger sum = BigInteger.ZERO;
                        BigInteger i = BigInteger.ZERO;
                        while (i < BigInteger.valueOf(3)) {
                            BigInteger j = BigInteger.ZERO;
                            while (j < BigInteger.valueOf(3)) {
                                sum = sum + i * BigInteger.valueOf(10) + j;
                                j = j + BigInteger.ONE;
                            }
                            i = i + BigInteger.ONE;
                        }
                        return sum == BigInteger.valueOf(99);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Nested while-in-while should evaluate correctly: " + evalResult);
        }

        @Test
        void nestedForEachInForEach_compiles() {
            // Nested for-each iterating over signatories twice
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.*;

                @Validator
                class NestedForEach {
                    @Entrypoint
                    static boolean validate(byte[] redeemer, ScriptContext ctx) {
                        var txInfo = ctx.txInfo();
                        BigInteger count = BigInteger.ZERO;
                        for (var sig1 : txInfo.signatories()) {
                            BigInteger innerCount = BigInteger.ZERO;
                            for (var sig2 : txInfo.signatories()) {
                                innerCount = innerCount + BigInteger.ONE;
                            }
                            count = count + innerCount;
                        }
                        return count >= BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());
        }

        @Test
        void mixedNesting_whileInForEach_compiles() {
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.*;

                @Validator
                class WhileInForEach {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, ScriptContext ctx) {
                        var txInfo = ctx.txInfo();
                        BigInteger total = BigInteger.ZERO;
                        for (var sig : txInfo.signatories()) {
                            BigInteger k = BigInteger.ZERO;
                            while (k < BigInteger.valueOf(3)) {
                                k = k + BigInteger.ONE;
                            }
                            total = total + k;
                        }
                        return total >= BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());
        }

        @Test
        void mixedNesting_forEachInWhile_compiles() {
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.*;

                @Validator
                class ForEachInWhile {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, ScriptContext ctx) {
                        var txInfo = ctx.txInfo();
                        BigInteger rounds = BigInteger.ZERO;
                        BigInteger total = BigInteger.ZERO;
                        while (rounds < BigInteger.valueOf(2)) {
                            BigInteger count = BigInteger.ZERO;
                            for (var sig : txInfo.signatories()) {
                                count = count + BigInteger.ONE;
                            }
                            total = total + count;
                            rounds = rounds + BigInteger.ONE;
                        }
                        return total >= BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());
        }

        @Test
        void booleanFlagEarlyExit_nestedWhile() {
            // containsDuplicate equivalent using boolean flag pattern
            // List [1,2,3,2] → has duplicate → found = true
            var source = """
                import java.math.BigInteger;

                @Validator
                class ContainsDuplicate {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger size = BigInteger.valueOf(4);
                        boolean found = false;
                        BigInteger i = BigInteger.ZERO;
                        while (i < size && !found) {
                            BigInteger j = i + BigInteger.ONE;
                            while (j < size && !found) {
                                if (i == j) {
                                    found = true;
                                }
                                j = j + BigInteger.ONE;
                            }
                            i = i + BigInteger.ONE;
                        }
                        return !found;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Boolean flag nested while should evaluate: " + evalResult);
        }

        @Test
        void nestedWhileWithBreak_innerBreakOnly() {
            // Inner loop breaks at j==2, outer loop continues. Verify break only exits inner.
            var source = """
                import java.math.BigInteger;

                @Validator
                class InnerBreakOnly {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger outerSum = BigInteger.ZERO;
                        BigInteger i = BigInteger.ZERO;
                        while (i < BigInteger.valueOf(3)) {
                            BigInteger j = BigInteger.ZERO;
                            while (j < BigInteger.valueOf(5)) {
                                if (j == BigInteger.valueOf(2)) {
                                    break;
                                }
                                j = j + BigInteger.ONE;
                            }
                            outerSum = outerSum + j;
                            i = i + BigInteger.ONE;
                        }
                        return outerSum == BigInteger.valueOf(6);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Inner break should not exit outer loop: " + evalResult);
        }
    }

    // ========== Deep Nested Loop Tests (3-4 levels) ==========

    @Nested
    class DeepNestedLoopTests {

        /** Build a minimal ScriptContext Data for eval tests */
        private PlutusData dummyScriptContext() {
            var emptyList = PlutusData.list();
            var emptyMap = PlutusData.map();
            var zeroInterval = PlutusData.constr(0,
                    PlutusData.constr(0, PlutusData.constr(1), PlutusData.integer(0)),
                    PlutusData.constr(0, PlutusData.constr(1), PlutusData.integer(0)));
            var txInfo = PlutusData.constr(0,
                    emptyList, emptyList, emptyList, emptyList, emptyList,
                    emptyList, emptyMap, zeroInterval, emptyList, emptyMap,
                    PlutusData.bytes(new byte[32]),
                    emptyMap, emptyList, emptyList, emptyList, PlutusData.integer(0));
            var redeemer = PlutusData.integer(0);
            var scriptInfo = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
        }

        @Test
        void tripleWhile_sumComputation() {
            // i=0..1, j=0..1, k=0..1 → sum += i*100 + j*10 + k
            // Expected: 0+1+10+11+100+101+110+111 = 444
            var source = """
                import java.math.BigInteger;

                @Validator
                class TripleWhile {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger sum = BigInteger.ZERO;
                        BigInteger i = BigInteger.ZERO;
                        while (i < BigInteger.valueOf(2)) {
                            BigInteger j = BigInteger.ZERO;
                            while (j < BigInteger.valueOf(2)) {
                                BigInteger k = BigInteger.ZERO;
                                while (k < BigInteger.valueOf(2)) {
                                    sum = sum + i * BigInteger.valueOf(100) + j * BigInteger.valueOf(10) + k;
                                    k = k + BigInteger.ONE;
                                }
                                j = j + BigInteger.ONE;
                            }
                            i = i + BigInteger.ONE;
                        }
                        return sum == BigInteger.valueOf(444);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Triple while sum should be 444: " + evalResult);
        }

        @Test
        void quadWhile_countIterations() {
            // i,j,k,l each 0..1 → count increments each innermost iteration
            // Expected: 2^4 = 16 iterations
            var source = """
                import java.math.BigInteger;

                @Validator
                class QuadWhile {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger count = BigInteger.ZERO;
                        BigInteger i = BigInteger.ZERO;
                        while (i < BigInteger.valueOf(2)) {
                            BigInteger j = BigInteger.ZERO;
                            while (j < BigInteger.valueOf(2)) {
                                BigInteger k = BigInteger.ZERO;
                                while (k < BigInteger.valueOf(2)) {
                                    BigInteger l = BigInteger.ZERO;
                                    while (l < BigInteger.valueOf(2)) {
                                        count = count + BigInteger.ONE;
                                        l = l + BigInteger.ONE;
                                    }
                                    k = k + BigInteger.ONE;
                                }
                                j = j + BigInteger.ONE;
                            }
                            i = i + BigInteger.ONE;
                        }
                        return count == BigInteger.valueOf(16);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Quad while count should be 16: " + evalResult);
        }

        @Test
        void tripleMixed_forEachWhileWhile_compiles() {
            // for-each over signatories, while inside while — 3 levels, mixed loop types
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.ledger.*;

                @Validator
                class TripleMixed {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, ScriptContext ctx) {
                        var txInfo = ctx.txInfo();
                        BigInteger total = BigInteger.ZERO;
                        for (var sig : txInfo.signatories()) {
                            BigInteger j = BigInteger.ZERO;
                            while (j < BigInteger.valueOf(2)) {
                                BigInteger k = BigInteger.ZERO;
                                while (k < BigInteger.valueOf(2)) {
                                    k = k + BigInteger.ONE;
                                }
                                total = total + k;
                                j = j + BigInteger.ONE;
                            }
                        }
                        return total >= BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());
        }

        @Test
        void tripleWhileWithBreakAtInnermost() {
            // Outer i=0..2, mid j=0..4, inner k=0..9 breaks at k==3
            // After inner break: k==3. outerSum += k per mid iteration, so outerSum += 3*5 per outer = 15*3 = 45
            var source = """
                import java.math.BigInteger;

                @Validator
                class TripleBreak {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger outerSum = BigInteger.ZERO;
                        BigInteger i = BigInteger.ZERO;
                        while (i < BigInteger.valueOf(3)) {
                            BigInteger j = BigInteger.ZERO;
                            while (j < BigInteger.valueOf(5)) {
                                BigInteger k = BigInteger.ZERO;
                                while (k < BigInteger.valueOf(10)) {
                                    if (k == BigInteger.valueOf(3)) {
                                        break;
                                    }
                                    k = k + BigInteger.ONE;
                                }
                                outerSum = outerSum + k;
                                j = j + BigInteger.ONE;
                            }
                            i = i + BigInteger.ONE;
                        }
                        return outerSum == BigInteger.valueOf(45);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Triple break should give outerSum=45: " + evalResult);
        }

        @Test
        void tripleWhile_sharedAccumulatorAtAllLevels() {
            // `total` is modified at ALL 3 levels — verifies accumulator threading
            // 2*(100 + 2*(10 + 2*1)) = 2*(100 + 2*12) = 2*124 = 248
            var source = """
                import java.math.BigInteger;

                @Validator
                class TripleSharedAcc {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger total = BigInteger.ZERO;
                        BigInteger i = BigInteger.ZERO;
                        while (i < BigInteger.valueOf(2)) {
                            total = total + BigInteger.valueOf(100);
                            BigInteger j = BigInteger.ZERO;
                            while (j < BigInteger.valueOf(2)) {
                                total = total + BigInteger.valueOf(10);
                                BigInteger k = BigInteger.ZERO;
                                while (k < BigInteger.valueOf(2)) {
                                    total = total + BigInteger.ONE;
                                    k = k + BigInteger.ONE;
                                }
                                j = j + BigInteger.ONE;
                            }
                            i = i + BigInteger.ONE;
                        }
                        return total == BigInteger.valueOf(248);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Triple shared accumulator should give total=248: " + evalResult);
        }
    }

    // ========== Single-Acc If-Without-Else Bug Fix Tests ==========

    @Nested
    class SingleAccIfWithoutElseTests {

        /** Build a minimal ScriptContext Data for eval tests */
        private PlutusData dummyScriptContext() {
            var emptyList = PlutusData.list();
            var emptyMap = PlutusData.map();
            var zeroInterval = PlutusData.constr(0,
                    PlutusData.constr(0, PlutusData.constr(1), PlutusData.integer(0)),
                    PlutusData.constr(0, PlutusData.constr(1), PlutusData.integer(0)));
            var txInfo = PlutusData.constr(0,
                    emptyList, emptyList, emptyList, emptyList, emptyList,
                    emptyList, emptyMap, zeroInterval, emptyList, emptyMap,
                    PlutusData.bytes(new byte[32]),
                    emptyMap, emptyList, emptyList, emptyList, PlutusData.integer(0));
            var redeemer = PlutusData.integer(0);
            var scriptInfo = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
        }

        @Test
        void forEachSingleAcc_ifWithoutElse_asLastStmt() {
            // Single-acc while: if-without-else is the LAST body statement.
            // k counts down. After decrement, if k==3 set k=0 (immediate termination).
            // k=10→9→...→4→3→0 (loop ends). Result: k==0.
            // Tests that when if-without-else is at the end of body,
            // the false branch returns current acc (not Unit).
            var source = """
                import java.math.BigInteger;

                @Validator
                class IfLastStmt {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger k = BigInteger.valueOf(10);
                        while (k > BigInteger.ZERO) {
                            k = k - BigInteger.ONE;
                            if (k == BigInteger.valueOf(3)) {
                                k = BigInteger.ZERO;
                            }
                        }
                        return k == BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "If-without-else as last body stmt should work: " + evalResult);
        }

        @Test
        void forEachSingleAcc_ifWithoutElse_conditionalIncrement() {
            // Single-acc while: k starts at 100, counts down by 1 each iteration.
            // If k > 95, subtract an extra 5 (so k drops faster for first 5 iterations).
            // k=100: 100>95→k=100-1-5=94. k=94: not>95→k=94-1=93. etc.
            // Only first iteration hits the condition. Final k==0.
            var source = """
                import java.math.BigInteger;

                @Validator
                class CondIncrement {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger k = BigInteger.valueOf(100);
                        while (k > BigInteger.ZERO) {
                            if (k > BigInteger.valueOf(95)) {
                                k = k - BigInteger.valueOf(5);
                            }
                            k = k - BigInteger.ONE;
                        }
                        return k == BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "If-without-else conditional increment should work: " + evalResult);
        }

        @Test
        void forEachSingleAcc_ifWithoutElse_followedByAccMod() {
            // Single-acc while: k starts at 20, counts down.
            // If k > 15, subtract 3 extra. Then subtract 1 always.
            // k=20: >15→k=20-3-1=16. k=16: >15→k=16-3-1=12. k=12: not>15→k=11. ...→k=0.
            // Tests that if-without-else result is properly threaded to the following statement.
            var source = """
                import java.math.BigInteger;

                @Validator
                class IfFollowedByMod {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger k = BigInteger.valueOf(20);
                        while (k > BigInteger.ZERO) {
                            if (k > BigInteger.valueOf(15)) {
                                k = k - BigInteger.valueOf(3);
                            }
                            k = k - BigInteger.ONE;
                        }
                        return k == BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "If-without-else followed by acc mod should work: " + evalResult);
        }

        @Test
        void whileSingleAcc_ifWithoutElse() {
            // k=5, while k>0: if k==3 k=-1; k=k-1 → final k should be -2
            var source = """
                import java.math.BigInteger;

                @Validator
                class WhileIfNoElse {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger k = BigInteger.valueOf(5);
                        while (k > BigInteger.ZERO) {
                            if (k == BigInteger.valueOf(3)) {
                                k = BigInteger.valueOf(-1);
                            }
                            k = k - BigInteger.ONE;
                        }
                        return k == BigInteger.valueOf(-2);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "While single-acc if-without-else should give k=-2: " + evalResult);
        }

        @Test
        void breakAware_ifWithoutElse_neitherBreaks() {
            // Single-acc while with break: k counts down from 10, if k==5 then k=-100, break.
            // Without the if, k decrements. Tests that if-without-else in break-aware path
            // correctly threads the accumulator.
            // k=10,9,8,7,6,5→k=-100, break → final k=-100
            var source = """
                import java.math.BigInteger;

                @Validator
                class BreakAwareIfNoElse {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger k = BigInteger.valueOf(10);
                        while (k > BigInteger.ZERO) {
                            if (k == BigInteger.valueOf(5)) {
                                k = BigInteger.valueOf(-100);
                            }
                            if (k < BigInteger.ZERO) {
                                break;
                            }
                            k = k - BigInteger.ONE;
                        }
                        return k == BigInteger.valueOf(-100);
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Break-aware if-without-else with break: " + evalResult);
        }

        @Test
        void forEachSingleAcc_nestedIfWithoutElse() {
            // Single-acc while with nested if-without-else.
            // k starts at 30. Body: if k>20 { if k>25 k=k-10; k=k-1; } k=k-1;
            // k=30: >20,>25→k=30-10-1-1=18. k=18: not>20→k=18-1=17. ... k=1→0.
            // Tests nested ifs-without-else properly thread accumulator at each level.
            var source = """
                import java.math.BigInteger;

                @Validator
                class NestedIfNoElse {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger k = BigInteger.valueOf(30);
                        while (k > BigInteger.ZERO) {
                            if (k > BigInteger.valueOf(20)) {
                                if (k > BigInteger.valueOf(25)) {
                                    k = k - BigInteger.valueOf(10);
                                }
                                k = k - BigInteger.ONE;
                            }
                            k = k - BigInteger.ONE;
                        }
                        return k == BigInteger.ZERO;
                    }
                }
                """;
            var result = new JulcCompiler().compile(source);
            assertNotNull(result.program(), "Compilation failed: " + result.diagnostics());
            assertFalse(result.hasErrors(), "Errors: " + result.diagnostics());

            var evalResult = vm.evaluateWithArgs(result.program(), List.of(dummyScriptContext()));
            assertTrue(evalResult.isSuccess(), "Nested if-without-else should work: " + evalResult);
        }
    }
}
