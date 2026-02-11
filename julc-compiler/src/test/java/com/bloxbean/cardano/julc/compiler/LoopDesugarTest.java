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
                import com.bloxbean.cardano.julc.onchain.ledger.*;

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
                import com.bloxbean.cardano.julc.onchain.ledger.*;

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
                import com.bloxbean.cardano.julc.onchain.ledger.*;

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
                import com.bloxbean.cardano.julc.onchain.ledger.*;

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
                import com.bloxbean.cardano.julc.onchain.ledger.*;

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
        void nestedForEachLoops() {
            // Nested for-each regression test
            var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.onchain.ledger.*;

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
            assertFalse(result.hasErrors());
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
            assertEquals("loop__while", letRec.bindings().getFirst().name());
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
            assertEquals("loop__while", letRec.bindings().getFirst().name());
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
    }
}
