package com.bloxbean.cardano.julc.stdlib;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.stdlib.legacy.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the plutus-stdlib module.
 * <p>
 * Each test builds PIR terms using the stdlib functions, lowers them to UPLC
 * via UplcGenerator, and evaluates them via JulcVm to verify correctness.
 */
class StdlibTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    // ---- Helper methods ----

    /**
     * Evaluate a PIR term and return the result.
     */
    private EvalResult evalPir(PirTerm pir) {
        var uplc = new UplcGenerator().generate(pir);
        return vm.evaluate(Program.plutusV3(uplc));
    }

    /**
     * Evaluate a PIR term and assert it returns an integer.
     */
    private BigInteger evalInteger(PirTerm pir) {
        var result = evalPir(pir);
        assertTrue(result.isSuccess(), "Expected success but got: " + result);
        var term = ((EvalResult.Success) result).resultTerm();
        assertInstanceOf(Term.Const.class, term);
        var val = ((Term.Const) term).value();
        assertInstanceOf(Constant.IntegerConst.class, val);
        return ((Constant.IntegerConst) val).value();
    }

    /**
     * Evaluate a PIR term and assert it returns a boolean.
     */
    private boolean evalBool(PirTerm pir) {
        var result = evalPir(pir);
        assertTrue(result.isSuccess(), "Expected success but got: " + result);
        var term = ((EvalResult.Success) result).resultTerm();
        assertInstanceOf(Term.Const.class, term);
        var val = ((Term.Const) term).value();
        assertInstanceOf(Constant.BoolConst.class, val);
        return ((Constant.BoolConst) val).value();
    }

    /**
     * Build an empty Data list: MkNilData(())
     */
    private PirTerm emptyDataList() {
        return new PirTerm.App(
                new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
    }

    /**
     * Prepend a Data element to a Data list: MkCons(elem, list)
     */
    private PirTerm consData(PirTerm elem, PirTerm list) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), elem),
                list);
    }

    /**
     * Wrap an integer as Data: IData(n)
     */
    private PirTerm iData(long n) {
        return new PirTerm.App(
                new PirTerm.Builtin(DefaultFun.IData),
                new PirTerm.Const(Constant.integer(BigInteger.valueOf(n))));
    }

    /**
     * Build a Data list of integers: [a, b, c, ...]
     */
    private PirTerm intDataList(long... values) {
        PirTerm list = emptyDataList();
        for (int i = values.length - 1; i >= 0; i--) {
            list = consData(iData(values[i]), list);
        }
        return list;
    }

    /**
     * Build a predicate that checks if UnIData(x) > threshold.
     */
    private PirTerm greaterThanPredicate(long threshold) {
        // \x -> LessThanInteger(threshold, UnIData(x))
        return new PirTerm.Lam("x", new PirType.DataType(),
                new PirTerm.App(
                        new PirTerm.App(
                                new PirTerm.Builtin(DefaultFun.LessThanInteger),
                                new PirTerm.Const(Constant.integer(BigInteger.valueOf(threshold)))),
                        new PirTerm.App(
                                new PirTerm.Builtin(DefaultFun.UnIData),
                                new PirTerm.Var("x", new PirType.DataType()))));
    }

    /**
     * Build a predicate that checks if UnIData(x) equals target.
     */
    private PirTerm equalsIntPredicate(long target) {
        // \x -> EqualsInteger(target, UnIData(x))
        return new PirTerm.Lam("x", new PirType.DataType(),
                new PirTerm.App(
                        new PirTerm.App(
                                new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                new PirTerm.Const(Constant.integer(BigInteger.valueOf(target)))),
                        new PirTerm.App(
                                new PirTerm.Builtin(DefaultFun.UnIData),
                                new PirTerm.Var("x", new PirType.DataType()))));
    }

    // =========================================================================
    // ListsLib Tests
    // =========================================================================

    @Nested
    class IsEmptyTests {
        @Test
        void isEmptyOnEmptyList() {
            var pir = ListsLib.isEmpty(emptyDataList());
            assertTrue(evalBool(pir));
        }

        @Test
        void isEmptyOnNonEmptyList() {
            var pir = ListsLib.isEmpty(intDataList(1, 2, 3));
            assertFalse(evalBool(pir));
        }

        @Test
        void isEmptyOnSingleElementList() {
            var pir = ListsLib.isEmpty(intDataList(42));
            assertFalse(evalBool(pir));
        }
    }

    @Nested
    class LengthTests {
        @Test
        void lengthOfEmptyList() {
            var pir = ListsLib.length(emptyDataList());
            assertEquals(BigInteger.ZERO, evalInteger(pir));
        }

        @Test
        void lengthOfSingleElementList() {
            var pir = ListsLib.length(intDataList(99));
            assertEquals(BigInteger.ONE, evalInteger(pir));
        }

        @Test
        void lengthOfThreeElementList() {
            var pir = ListsLib.length(intDataList(10, 20, 30));
            assertEquals(BigInteger.valueOf(3), evalInteger(pir));
        }
    }

    @Nested
    class FoldlTests {
        @Test
        void foldlSumEmptyList() {
            // foldl (\acc x -> acc + UnIData(x)) 0 []
            var f = sumFoldFn();
            var pir = ListsLib.foldl(f, new PirTerm.Const(Constant.integer(BigInteger.ZERO)),
                    emptyDataList());
            assertEquals(BigInteger.ZERO, evalInteger(pir));
        }

        @Test
        void foldlSumThreeElements() {
            // foldl (\acc x -> acc + UnIData(x)) 0 [10, 20, 30]
            var f = sumFoldFn();
            var pir = ListsLib.foldl(f, new PirTerm.Const(Constant.integer(BigInteger.ZERO)),
                    intDataList(10, 20, 30));
            assertEquals(BigInteger.valueOf(60), evalInteger(pir));
        }

        @Test
        void foldlWithInitialAccumulator() {
            // foldl (\acc x -> acc + UnIData(x)) 100 [1, 2, 3]
            var f = sumFoldFn();
            var pir = ListsLib.foldl(f, new PirTerm.Const(Constant.integer(BigInteger.valueOf(100))),
                    intDataList(1, 2, 3));
            assertEquals(BigInteger.valueOf(106), evalInteger(pir));
        }

        @Test
        void foldlProductOfElements() {
            // foldl (\acc x -> acc * UnIData(x)) 1 [2, 3, 4]
            var accVar = new PirTerm.Var("acc", new PirType.IntegerType());
            var xVar = new PirTerm.Var("x", new PirType.DataType());
            var mul = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger), accVar),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), xVar));
            var f = new PirTerm.Lam("acc", new PirType.IntegerType(),
                    new PirTerm.Lam("x", new PirType.DataType(), mul));
            var pir = ListsLib.foldl(f, new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                    intDataList(2, 3, 4));
            assertEquals(BigInteger.valueOf(24), evalInteger(pir));
        }

        /**
         * Build fold function: \acc x -> AddInteger(acc, UnIData(x))
         */
        private PirTerm sumFoldFn() {
            var accVar = new PirTerm.Var("acc", new PirType.IntegerType());
            var xVar = new PirTerm.Var("x", new PirType.DataType());
            var add = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), accVar),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), xVar));
            return new PirTerm.Lam("acc", new PirType.IntegerType(),
                    new PirTerm.Lam("x", new PirType.DataType(), add));
        }
    }

    @Nested
    class AnyTests {
        @Test
        void anyOnEmptyListReturnsFalse() {
            var pred = greaterThanPredicate(0);
            var pir = ListsLib.any(emptyDataList(), pred);
            assertFalse(evalBool(pir));
        }

        @Test
        void anyWithMatchReturnsTrue() {
            // any [1, 5, 10] (x > 7) -> true (10 > 7)
            var pred = greaterThanPredicate(7);
            var pir = ListsLib.any(intDataList(1, 5, 10), pred);
            assertTrue(evalBool(pir));
        }

        @Test
        void anyWithNoMatchReturnsFalse() {
            // any [1, 2, 3] (x > 10) -> false
            var pred = greaterThanPredicate(10);
            var pir = ListsLib.any(intDataList(1, 2, 3), pred);
            assertFalse(evalBool(pir));
        }

        @Test
        void anyAllMatch() {
            // any [10, 20, 30] (x > 5) -> true
            var pred = greaterThanPredicate(5);
            var pir = ListsLib.any(intDataList(10, 20, 30), pred);
            assertTrue(evalBool(pir));
        }
    }

    @Nested
    class AllTests {
        @Test
        void allOnEmptyListReturnsTrue() {
            var pred = greaterThanPredicate(0);
            var pir = ListsLib.all(emptyDataList(), pred);
            assertTrue(evalBool(pir));
        }

        @Test
        void allWhenAllMatchReturnsTrue() {
            // all [10, 20, 30] (x > 5) -> true
            var pred = greaterThanPredicate(5);
            var pir = ListsLib.all(intDataList(10, 20, 30), pred);
            assertTrue(evalBool(pir));
        }

        @Test
        void allWhenSomeDontMatchReturnsFalse() {
            // all [1, 20, 30] (x > 5) -> false (1 is not > 5)
            var pred = greaterThanPredicate(5);
            var pir = ListsLib.all(intDataList(1, 20, 30), pred);
            assertFalse(evalBool(pir));
        }

        @Test
        void allWhenNoneMatchReturnsFalse() {
            // all [1, 2, 3] (x > 10) -> false
            var pred = greaterThanPredicate(10);
            var pir = ListsLib.all(intDataList(1, 2, 3), pred);
            assertFalse(evalBool(pir));
        }
    }

    @Nested
    class FindTests {
        private PlutusData extractResultData(EvalResult result) {
            assertTrue(result.isSuccess(), "Expected success but got: " + result);
            var term = ((EvalResult.Success) result).resultTerm();
            assertInstanceOf(Term.Const.class, term);
            var constant = ((Term.Const) term).value();
            assertInstanceOf(Constant.DataConst.class, constant);
            return ((Constant.DataConst) constant).value();
        }

        @Test
        void findOnEmptyListReturnsNone() {
            var pred = equalsIntPredicate(42);
            var result = evalPir(ListsLib.find(emptyDataList(), pred));
            // None = ConstrData(1, [])
            var data = extractResultData(result);
            assertInstanceOf(PlutusData.ConstrData.class, data);
            var constr = (PlutusData.ConstrData) data;
            assertEquals(1, constr.tag());
            assertTrue(constr.fields().isEmpty());
        }

        @Test
        void findExistingElementReturnsSome() {
            // find [10, 20, 30] (x == 20) -> Some(IData(20))
            var pred = equalsIntPredicate(20);
            var result = evalPir(ListsLib.find(intDataList(10, 20, 30), pred));
            // Some(x) = ConstrData(0, [x])
            var data = extractResultData(result);
            assertInstanceOf(PlutusData.ConstrData.class, data);
            var constr = (PlutusData.ConstrData) data;
            assertEquals(0, constr.tag());
            assertEquals(1, constr.fields().size());
            var inner = constr.fields().getFirst();
            assertInstanceOf(PlutusData.IntData.class, inner);
            assertEquals(BigInteger.valueOf(20), ((PlutusData.IntData) inner).value());
        }

        @Test
        void findReturnsFirstMatch() {
            // find [5, 10, 15, 20] (x > 7) -> Some(IData(10)) (first match)
            var pred = greaterThanPredicate(7);
            var result = evalPir(ListsLib.find(intDataList(5, 10, 15, 20), pred));
            var data = extractResultData(result);
            assertInstanceOf(PlutusData.ConstrData.class, data);
            var constr = (PlutusData.ConstrData) data;
            assertEquals(0, constr.tag());
            var inner = constr.fields().getFirst();
            assertEquals(BigInteger.TEN, ((PlutusData.IntData) inner).value());
        }

        @Test
        void findNoMatchReturnsNone() {
            // find [1, 2, 3] (x == 99) -> None
            var pred = equalsIntPredicate(99);
            var result = evalPir(ListsLib.find(intDataList(1, 2, 3), pred));
            var data = extractResultData(result);
            assertInstanceOf(PlutusData.ConstrData.class, data);
            assertEquals(1, ((PlutusData.ConstrData) data).tag());
        }
    }

    // =========================================================================
    // CryptoLib Tests
    // =========================================================================

    @Nested
    class CryptoTests {
        @Test
        void sha2_256ProducesHash() {
            var input = new PirTerm.Const(Constant.byteString(new byte[]{1, 2, 3}));
            var pir = CryptoLib.sha2_256(input);
            var result = evalPir(pir);
            assertTrue(result.isSuccess(), "Expected success but got: " + result);
            var term = ((EvalResult.Success) result).resultTerm();
            assertInstanceOf(Term.Const.class, term);
            var val = ((Term.Const) term).value();
            assertInstanceOf(Constant.ByteStringConst.class, val);
            // SHA2-256 always produces 32 bytes
            assertEquals(32, ((Constant.ByteStringConst) val).value().length);
        }

        @Test
        void blake2b_256ProducesHash() {
            var input = new PirTerm.Const(Constant.byteString(new byte[]{4, 5, 6}));
            var pir = CryptoLib.blake2b_256(input);
            var result = evalPir(pir);
            assertTrue(result.isSuccess(), "Expected success but got: " + result);
            var term = ((EvalResult.Success) result).resultTerm();
            assertInstanceOf(Term.Const.class, term);
            var val = ((Term.Const) term).value();
            assertInstanceOf(Constant.ByteStringConst.class, val);
            assertEquals(32, ((Constant.ByteStringConst) val).value().length);
        }

        @Test
        void sha2_256EmptyInput() {
            var input = new PirTerm.Const(Constant.byteString(new byte[]{}));
            var pir = CryptoLib.sha2_256(input);
            var result = evalPir(pir);
            assertTrue(result.isSuccess(), "Expected success but got: " + result);
            var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
            assertEquals(32, ((Constant.ByteStringConst) val).value().length);
        }

        @Test
        void blake2b_256DifferentInputsDifferentHashes() {
            var input1 = new PirTerm.Const(Constant.byteString(new byte[]{1}));
            var input2 = new PirTerm.Const(Constant.byteString(new byte[]{2}));
            var result1 = evalPir(CryptoLib.blake2b_256(input1));
            var result2 = evalPir(CryptoLib.blake2b_256(input2));
            assertTrue(result1.isSuccess());
            assertTrue(result2.isSuccess());
            var hash1 = ((Constant.ByteStringConst) ((Term.Const) ((EvalResult.Success) result1).resultTerm()).value()).value();
            var hash2 = ((Constant.ByteStringConst) ((Term.Const) ((EvalResult.Success) result2).resultTerm()).value()).value();
            assertFalse(java.util.Arrays.equals(hash1, hash2));
        }
    }

    // =========================================================================
    // ValuesLib Tests
    // =========================================================================

    @Nested
    class ValuesTests {
        @Test
        void lovelaceOfSimpleValue() {
            // Value: Map[ (B"", Map[ (B"", I(1000000)) ]) ]
            // Encoded as Data: MapData(MkCons(MkPairData(BData(""), MapData(MkCons(MkPairData(BData(""), IData(1000000)), MkNilPairData()))), MkNilPairData()))
            var lovelaceAmount = 1_000_000L;

            // Build inner map: { "" -> 1000000 }
            var innerPair = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                                    new PirTerm.Const(Constant.byteString(new byte[]{})))),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(lovelaceAmount)))));
            var emptyPairList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                    new PirTerm.Const(Constant.unit()));
            var innerList = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), innerPair),
                    emptyPairList);
            var innerMap = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), innerList);

            // Build outer map: { "" -> innerMap }
            var outerPair = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                                    new PirTerm.Const(Constant.byteString(new byte[]{})))),
                    innerMap);
            var outerList = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), outerPair),
                    emptyPairList);
            var valueData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), outerList);

            var pir = ValuesLib.lovelaceOf(valueData);
            assertEquals(BigInteger.valueOf(lovelaceAmount), evalInteger(pir));
        }

        @Test
        void lovelaceOfLargeAmount() {
            // Value with 45 ADA = 45_000_000 lovelace
            var lovelaceAmount = 45_000_000L;

            var innerPair = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                                    new PirTerm.Const(Constant.byteString(new byte[]{})))),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(lovelaceAmount)))));
            var emptyPairList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                    new PirTerm.Const(Constant.unit()));
            var innerList = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), innerPair),
                    emptyPairList);
            var innerMap = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), innerList);

            var outerPair = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                                    new PirTerm.Const(Constant.byteString(new byte[]{})))),
                    innerMap);
            var outerList = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), outerPair),
                    emptyPairList);
            var valueData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), outerList);

            var pir = ValuesLib.lovelaceOf(valueData);
            assertEquals(BigInteger.valueOf(lovelaceAmount), evalInteger(pir));
        }
    }

    // =========================================================================
    // ContextsLib Tests
    // =========================================================================

    @Nested
    class ContextsTests {

        /**
         * Build a mock TxInfo as Data with signatories list.
         * TxInfo = Constr(0, [inputs, refInputs, outputs, fee, mint, certs, wdrawals, validRange, signatories, ...])
         * We place placeholder Data for fields 0-7, then the signatories list at index 8.
         */
        private PirTerm mockTxInfoWithSignatories(PirTerm signatories) {
            // Build the TxInfo fields list: 16 fields, signatories at index 8
            // For simplicity, build ConstrData(0, [field0, ..., field8=signatories, ...field15])
            // Use IData(0) for placeholder fields and ListData(signatories) for index 8
            var zero = iData(0);
            // Build fields list from right to left (fields 15..0)
            PirTerm fieldsList = emptyDataList();
            for (int i = 15; i >= 0; i--) {
                PirTerm field;
                if (i == 8) {
                    // Signatories field: ListData(signatories)
                    field = new PirTerm.App(new PirTerm.Builtin(DefaultFun.ListData), signatories);
                } else {
                    field = zero;
                }
                fieldsList = consData(field, fieldsList);
            }
            // ConstrData(0, fieldsList)
            return new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                    fieldsList);
        }

        /**
         * Build a PubKeyHash as Data: BData(bs) — raw BytesData in Cardano V3
         */
        private PirTerm pkhData(byte[] bs) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                    new PirTerm.Const(Constant.byteString(bs)));
        }

        @Test
        void getTxInfoExtractsFirstField() {
            // ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
            // getTxInfo should return the first field
            var txInfoData = iData(42);
            var redeemer = iData(0);
            var scriptInfo = iData(0);
            PirTerm fields = consData(scriptInfo, emptyDataList());
            fields = consData(redeemer, fields);
            fields = consData(txInfoData, fields);
            var ctx = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                    fields);
            var pir = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                    ContextsLib.getTxInfo(ctx));
            assertEquals(BigInteger.valueOf(42), evalInteger(pir));
        }

        @Test
        void getRedeemerExtractsSecondField() {
            var txInfoData = iData(0);
            var redeemer = iData(99);
            var scriptInfo = iData(0);
            PirTerm fields = consData(scriptInfo, emptyDataList());
            fields = consData(redeemer, fields);
            fields = consData(txInfoData, fields);
            var ctx = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                    fields);
            var pir = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                    ContextsLib.getRedeemer(ctx));
            assertEquals(BigInteger.valueOf(99), evalInteger(pir));
        }

        @Test
        void txInfoSignatoriesExtractsList() {
            // Build signatories list with 2 pkh entries
            var pkh1 = pkhData(new byte[]{1, 2, 3});
            var pkh2 = pkhData(new byte[]{4, 5, 6});
            PirTerm sigsList = consData(pkh2, emptyDataList());
            sigsList = consData(pkh1, sigsList);

            var txInfo = mockTxInfoWithSignatories(sigsList);
            var pir = ContextsLib.txInfoSignatories(txInfo);
            // The result should be a list with 2 elements
            var lengthPir = ListsLib.length(pir);
            assertEquals(BigInteger.valueOf(2), evalInteger(lengthPir));
        }

        @Test
        void signedByFindsMatchingSignatory() {
            var targetPkh = new byte[]{1, 2, 3, 4, 5};
            var otherPkh = new byte[]{9, 8, 7, 6, 5};

            var pkh1 = pkhData(otherPkh);
            var pkh2 = pkhData(targetPkh);
            PirTerm sigsList = consData(pkh2, emptyDataList());
            sigsList = consData(pkh1, sigsList);

            var txInfo = mockTxInfoWithSignatories(sigsList);
            var target = pkhData(targetPkh);

            var pir = ContextsLib.signedBy(txInfo, target);
            assertTrue(evalBool(pir));
        }

        @Test
        void signedByReturnsFalseWhenNotFound() {
            var targetPkh = new byte[]{1, 2, 3, 4, 5};
            var otherPkh1 = new byte[]{9, 8, 7, 6, 5};
            var otherPkh2 = new byte[]{10, 11, 12, 13, 14};

            var pkh1 = pkhData(otherPkh1);
            var pkh2 = pkhData(otherPkh2);
            PirTerm sigsList = consData(pkh2, emptyDataList());
            sigsList = consData(pkh1, sigsList);

            var txInfo = mockTxInfoWithSignatories(sigsList);
            var target = pkhData(targetPkh);

            var pir = ContextsLib.signedBy(txInfo, target);
            assertFalse(evalBool(pir));
        }

        @Test
        void signedByOnEmptySignatoriesReturnsFalse() {
            PirTerm sigsList = emptyDataList();
            var txInfo = mockTxInfoWithSignatories(sigsList);
            var target = pkhData(new byte[]{1, 2, 3});

            var pir = ContextsLib.signedBy(txInfo, target);
            assertFalse(evalBool(pir));
        }
    }

    // =========================================================================
    // IntervalLib Tests
    // =========================================================================

    @Nested
    class IntervalTests {

        /**
         * Build a Finite IntervalBound as Data: Constr(0, [Constr(1, [IData(time)]), Bool])
         */
        private PirTerm finiteBound(long time, boolean inclusive) {
            var timeData = iData(time);
            // Finite = Constr(1, [IData(time)])
            PirTerm finiteFields = consData(timeData, emptyDataList());
            var finiteType = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                            new PirTerm.Const(Constant.integer(BigInteger.ONE))),
                    finiteFields);
            // Bool: True=Constr(1,[]), False=Constr(0,[])
            var boolData = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(inclusive ? 1 : 0)))),
                    emptyDataList());
            // IntervalBound = Constr(0, [boundType, isInclusive])
            PirTerm boundFields = consData(boolData, emptyDataList());
            boundFields = consData(finiteType, boundFields);
            return new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                    boundFields);
        }

        /**
         * Build an infinity bound: NegInf=Constr(0,[]) or PosInf=Constr(2,[])
         */
        private PirTerm infinityBound(boolean negInf, boolean inclusive) {
            int tag = negInf ? 0 : 2;
            var infType = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(tag)))),
                    emptyDataList());
            var boolData = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(inclusive ? 1 : 0)))),
                    emptyDataList());
            PirTerm boundFields = consData(boolData, emptyDataList());
            boundFields = consData(infType, boundFields);
            return new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                    boundFields);
        }

        /**
         * Build an Interval as Data: Constr(0, [fromBound, toBound])
         */
        private PirTerm interval(PirTerm fromBound, PirTerm toBound) {
            PirTerm fields = consData(toBound, emptyDataList());
            fields = consData(fromBound, fields);
            return new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                    fields);
        }

        private PirTerm rawInt(long n) {
            return new PirTerm.Const(Constant.integer(BigInteger.valueOf(n)));
        }

        @Test
        void containsAlwaysIntervalReturnsTrue() {
            // (-inf, +inf) contains any time
            var alwaysInterval = interval(
                    infinityBound(true, true),   // NegInf, inclusive
                    infinityBound(false, true));  // PosInf, inclusive
            var pir = IntervalLib.contains(alwaysInterval, rawInt(12345));
            assertTrue(evalBool(pir));
        }

        @Test
        void containsFiniteInclusiveIntervalInsideReturnsTrue() {
            // [100, 200] contains 150
            var ival = interval(finiteBound(100, true), finiteBound(200, true));
            var pir = IntervalLib.contains(ival, rawInt(150));
            assertTrue(evalBool(pir));
        }

        @Test
        void containsFiniteInclusiveIntervalAtLowerBound() {
            // [100, 200] contains 100
            var ival = interval(finiteBound(100, true), finiteBound(200, true));
            var pir = IntervalLib.contains(ival, rawInt(100));
            assertTrue(evalBool(pir));
        }

        @Test
        void containsFiniteInclusiveIntervalAtUpperBound() {
            // [100, 200] contains 200
            var ival = interval(finiteBound(100, true), finiteBound(200, true));
            var pir = IntervalLib.contains(ival, rawInt(200));
            assertTrue(evalBool(pir));
        }

        @Test
        void containsFiniteInclusiveIntervalBelowReturnsTrue() {
            // [100, 200] does NOT contain 99
            var ival = interval(finiteBound(100, true), finiteBound(200, true));
            var pir = IntervalLib.contains(ival, rawInt(99));
            assertFalse(evalBool(pir));
        }

        @Test
        void containsFiniteInclusiveIntervalAboveReturnsFalse() {
            // [100, 200] does NOT contain 201
            var ival = interval(finiteBound(100, true), finiteBound(200, true));
            var pir = IntervalLib.contains(ival, rawInt(201));
            assertFalse(evalBool(pir));
        }

        @Test
        void containsExclusiveLowerBound() {
            // (100, 200] does NOT contain 100
            var ival = interval(finiteBound(100, false), finiteBound(200, true));
            var pir = IntervalLib.contains(ival, rawInt(100));
            assertFalse(evalBool(pir));
        }

        @Test
        void containsExclusiveUpperBound() {
            // [100, 200) does NOT contain 200
            var ival = interval(finiteBound(100, true), finiteBound(200, false));
            var pir = IntervalLib.contains(ival, rawInt(200));
            assertFalse(evalBool(pir));
        }

        @Test
        void containsAfterInterval() {
            // [1000, +inf) contains 2000
            var ival = interval(finiteBound(1000, true), infinityBound(false, true));
            var pir = IntervalLib.contains(ival, rawInt(2000));
            assertTrue(evalBool(pir));
        }

        @Test
        void containsAfterIntervalBelowReturnsFalse() {
            // [1000, +inf) does NOT contain 999
            var ival = interval(finiteBound(1000, true), infinityBound(false, true));
            var pir = IntervalLib.contains(ival, rawInt(999));
            assertFalse(evalBool(pir));
        }

        @Test
        void containsBeforeInterval() {
            // (-inf, 1000] contains 500
            var ival = interval(infinityBound(true, true), finiteBound(1000, true));
            var pir = IntervalLib.contains(ival, rawInt(500));
            assertTrue(evalBool(pir));
        }

        @Test
        void containsBeforeIntervalAboveReturnsFalse() {
            // (-inf, 1000] does NOT contain 1001
            var ival = interval(infinityBound(true, true), finiteBound(1000, true));
            var pir = IntervalLib.contains(ival, rawInt(1001));
            assertFalse(evalBool(pir));
        }
    }

    // =========================================================================
    // Extended ValuesLib Tests
    // =========================================================================

    @Nested
    class ExtendedValuesTests {

        /**
         * Build a simple Value with only lovelace.
         */
        private PirTerm simpleValue(long lovelaceAmount) {
            var innerPair = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                                    new PirTerm.Const(Constant.byteString(new byte[]{})))),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(lovelaceAmount)))));
            var emptyPairList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                    new PirTerm.Const(Constant.unit()));
            var innerList = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), innerPair),
                    emptyPairList);
            var innerMap = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), innerList);

            var outerPair = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                                    new PirTerm.Const(Constant.byteString(new byte[]{})))),
                    innerMap);
            var outerList = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), outerPair),
                    emptyPairList);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), outerList);
        }

        /**
         * Build a multi-asset Value: { "" -> {"" -> lovelace}, policy -> {token -> amount} }
         */
        private PirTerm multiAssetValue(long lovelace, byte[] policy, byte[] token, long amount) {
            var emptyPairList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                    new PirTerm.Const(Constant.unit()));

            // Inner map for lovelace: { "" -> lovelace }
            var lovelacePair = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                                    new PirTerm.Const(Constant.byteString(new byte[]{})))),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(lovelace)))));
            var lovelaceInner = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), lovelacePair),
                    emptyPairList);
            var lovelaceMap = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), lovelaceInner);

            // Inner map for token: { token -> amount }
            var tokenPair = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                                    new PirTerm.Const(Constant.byteString(token)))),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(amount)))));
            var tokenInner = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), tokenPair),
                    emptyPairList);
            var tokenMap = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), tokenInner);

            // Outer map entries
            var lovelaceEntry = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                                    new PirTerm.Const(Constant.byteString(new byte[]{})))),
                    lovelaceMap);
            var tokenEntry = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                                    new PirTerm.Const(Constant.byteString(policy)))),
                    tokenMap);

            // Build outer list: [lovelaceEntry, tokenEntry]
            var outerList = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), tokenEntry),
                    emptyPairList);
            outerList = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), lovelaceEntry),
                    outerList);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), outerList);
        }

        @Test
        void geqWhenGreater() {
            var a = simpleValue(2_000_000);
            var b = simpleValue(1_000_000);
            var pir = ValuesLib.geq(a, b);
            assertTrue(evalBool(pir));
        }

        @Test
        void geqWhenEqual() {
            var a = simpleValue(1_000_000);
            var b = simpleValue(1_000_000);
            var pir = ValuesLib.geq(a, b);
            assertTrue(evalBool(pir));
        }

        @Test
        void geqWhenLess() {
            var a = simpleValue(500_000);
            var b = simpleValue(1_000_000);
            var pir = ValuesLib.geq(a, b);
            assertFalse(evalBool(pir));
        }

        @Test
        void assetOfFindsToken() {
            byte[] policy = {1, 2, 3};
            byte[] token = {4, 5};
            var value = multiAssetValue(2_000_000, policy, token, 100);
            var pir = ValuesLib.assetOf(value,
                    new PirTerm.Const(Constant.byteString(policy)),
                    new PirTerm.Const(Constant.byteString(token)));
            assertEquals(BigInteger.valueOf(100), evalInteger(pir));
        }

        @Test
        void assetOfReturnsZeroForMissingPolicy() {
            byte[] policy = {1, 2, 3};
            byte[] token = {4, 5};
            byte[] missingPolicy = {9, 9, 9};
            var value = multiAssetValue(2_000_000, policy, token, 100);
            var pir = ValuesLib.assetOf(value,
                    new PirTerm.Const(Constant.byteString(missingPolicy)),
                    new PirTerm.Const(Constant.byteString(token)));
            assertEquals(BigInteger.ZERO, evalInteger(pir));
        }

        @Test
        void assetOfReturnsZeroForMissingToken() {
            byte[] policy = {1, 2, 3};
            byte[] token = {4, 5};
            byte[] missingToken = {9, 9};
            var value = multiAssetValue(2_000_000, policy, token, 100);
            var pir = ValuesLib.assetOf(value,
                    new PirTerm.Const(Constant.byteString(policy)),
                    new PirTerm.Const(Constant.byteString(missingToken)));
            assertEquals(BigInteger.ZERO, evalInteger(pir));
        }
    }

    // =========================================================================
    // StdlibRegistry Tests
    // =========================================================================

    @Nested
    class RegistryTests {
        @Test
        void defaultRegistryContainsAllEntries() {
            var reg = StdlibRegistry.defaultRegistry();
            String B = "com.bloxbean.cardano.julc.stdlib.Builtins";
            String L = "com.bloxbean.cardano.julc.stdlib.lib.ListsLib";
            String CTX = "com.bloxbean.cardano.julc.stdlib.lib.ContextsLib";
            String VAL = "com.bloxbean.cardano.julc.stdlib.lib.ValuesLib";
            String CRYPTO = "com.bloxbean.cardano.julc.stdlib.lib.CryptoLib";
            String INTERVAL = "com.bloxbean.cardano.julc.stdlib.lib.IntervalLib";
            String BS = "com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib";
            String MAP = "com.bloxbean.cardano.julc.stdlib.lib.MapLib";
            String MATH = "com.bloxbean.cardano.julc.stdlib.lib.MathLib";
            String BW = "com.bloxbean.cardano.julc.stdlib.lib.BitwiseLib";
            String LEDGER = "com.bloxbean.cardano.julc.ledger.";
            // ListsLib HOF: 7 methods (non-HOF now compiled from Java source)
            assertTrue(reg.contains(L, "any"));
            assertTrue(reg.contains(L, "all"));
            assertTrue(reg.contains(L, "find"));
            assertTrue(reg.contains(L, "foldl"));
            assertTrue(reg.contains(L, "map"));
            assertTrue(reg.contains(L, "filter"));
            assertTrue(reg.contains(L, "zip"));
            // Migrated to Java source: length, isEmpty, head, tail, reverse, concat, nth, take, drop
            assertFalse(reg.contains(L, "length"));
            assertFalse(reg.contains(L, "isEmpty"));
            // ContextsLib: only trace stays as PIR (uses UPLC Text type)
            assertTrue(reg.contains(CTX, "trace"));
            // All other ContextsLib methods migrated to Java source
            assertFalse(reg.contains(CTX, "signedBy"));
            assertFalse(reg.contains(CTX, "getTxInfo"));
            // ValuesLib: all migrated to Java source
            assertFalse(reg.contains(VAL, "lovelaceOf"));
            assertFalse(reg.contains(VAL, "geq"));
            assertFalse(reg.contains(VAL, "assetOf"));
            // CryptoLib: all migrated to Java source
            assertFalse(reg.contains(CRYPTO, "sha2_256"));
            assertFalse(reg.contains(CRYPTO, "blake2b_256"));
            // IntervalLib: all migrated to Java source
            assertFalse(reg.contains(INTERVAL, "contains"));
            assertFalse(reg.contains(INTERVAL, "always"));
            // ByteStringLib: all migrated to Java source
            assertFalse(reg.contains(BS, "at"));
            assertFalse(reg.contains(BS, "length"));
            // MapLib: all migrated to Java source
            assertFalse(reg.contains(MAP, "lookup"));
            // MathLib: all migrated to Java source (including expMod)
            assertFalse(reg.contains(MATH, "abs"));
            assertFalse(reg.contains(MATH, "expMod"));
            // BitwiseLib: all migrated to Java source
            assertFalse(reg.contains(BW, "andByteString"));
            assertFalse(reg.contains(BW, "countSetBits"));
            // Java API delegates (Math.abs/max/min -> inline PIR)
            assertTrue(reg.contains("Math", "abs"));
            assertTrue(reg.contains("Math", "max"));
            assertTrue(reg.contains("Math", "min"));
            // Builtins: 60 raw UPLC builtins (22 original + 15 bytestring + 9 crypto
            //   + 10 bitwise + 2 constrTag/constrFields + 1 expModInteger + 1 toByteString)
            assertTrue(reg.contains(B, "headList"));
            assertTrue(reg.contains(B, "tailList"));
            assertTrue(reg.contains(B, "nullList"));
            assertTrue(reg.contains(B, "mkCons"));
            assertTrue(reg.contains(B, "mkNilData"));
            assertTrue(reg.contains(B, "fstPair"));
            assertTrue(reg.contains(B, "sndPair"));
            assertTrue(reg.contains(B, "mkPairData"));
            assertTrue(reg.contains(B, "mkNilPairData"));
            assertTrue(reg.contains(B, "constrData"));
            assertTrue(reg.contains(B, "iData"));
            assertTrue(reg.contains(B, "bData"));
            assertTrue(reg.contains(B, "listData"));
            assertTrue(reg.contains(B, "mapData"));
            assertTrue(reg.contains(B, "unConstrData"));
            assertTrue(reg.contains(B, "unIData"));
            assertTrue(reg.contains(B, "unBData"));
            assertTrue(reg.contains(B, "unListData"));
            assertTrue(reg.contains(B, "unMapData"));
            assertTrue(reg.contains(B, "equalsData"));
            assertTrue(reg.contains(B, "error"));
            assertTrue(reg.contains(B, "trace"));
            // New builtins from Milestone 10
            assertTrue(reg.contains(B, "indexByteString"));
            assertTrue(reg.contains(B, "sha2_256"));
            assertTrue(reg.contains(B, "andByteString"));
            assertTrue(reg.contains(B, "constrTag"));
            assertTrue(reg.contains(B, "constrFields"));
            assertTrue(reg.contains(B, "expModInteger"));
            assertTrue(reg.contains(B, "toByteString"));
            // Ledger type .of() factories (identity on-chain)
            assertTrue(reg.contains(LEDGER + "PubKeyHash", "of"));
            assertTrue(reg.contains(LEDGER + "ScriptHash", "of"));
            assertTrue(reg.contains(LEDGER + "ValidatorHash", "of"));
            assertTrue(reg.contains(LEDGER + "PolicyId", "of"));
            assertTrue(reg.contains(LEDGER + "TokenName", "of"));
            assertTrue(reg.contains(LEDGER + "DatumHash", "of"));
            assertTrue(reg.contains(LEDGER + "TxId", "of"));
            // Optional factory methods (registered under both simple and FQCN)
            assertTrue(reg.contains("Optional", "of"));
            assertTrue(reg.contains("Optional", "empty"));
            assertTrue(reg.contains("java.util.Optional", "of"));
            assertTrue(reg.contains("java.util.Optional", "empty"));
            // Total: 60 Builtins + 7 ListsLib HOF + 1 ContextsLib.trace + 3 Math delegates
            //   + 2 JulcList factories + 7 ledger .of() + 4 Optional factories = 84
            assertEquals(84, reg.size());
        }

        @Test
        void lookupNonExistentReturnsEmpty() {
            var reg = StdlibRegistry.defaultRegistry();
            assertTrue(reg.lookup("Foo", "bar", List.of()).isEmpty());
        }

        @Test
        void lookupListsLibAnyViaRegistry() {
            var reg = StdlibRegistry.defaultRegistry();
            // ListsLib.any is still in the registry (HOF method)
            assertTrue(reg.lookupBuilder("com.bloxbean.cardano.julc.stdlib.lib.ListsLib", "any").isPresent());
        }

        @Test
        void lookupMigratedMethodReturnsEmpty() {
            var reg = StdlibRegistry.defaultRegistry();
            // ListsLib.isEmpty was migrated to Java source, no longer in registry
            assertTrue(reg.lookup("com.bloxbean.cardano.julc.stdlib.lib.ListsLib", "isEmpty", List.of(emptyDataList())).isEmpty());
        }

        @Test
        void lookupWithWrongArgCountThrows() {
            var reg = StdlibRegistry.defaultRegistry();
            // ListsLib.any expects 2 args, call with 0
            assertThrows(IllegalArgumentException.class,
                    () -> reg.lookup("com.bloxbean.cardano.julc.stdlib.lib.ListsLib", "any", List.of()));
        }

        @Test
        void lookupMigratedCryptoReturnsEmpty() {
            var reg = StdlibRegistry.defaultRegistry();
            // CryptoLib methods are now compiled from Java source, not in registry
            assertFalse(reg.contains("com.bloxbean.cardano.julc.stdlib.lib.CryptoLib", "sha2_256"));
        }

        @Test
        void lookupContextsTraceViaRegistry() {
            var reg = StdlibRegistry.defaultRegistry();
            // Only trace stays in registry (uses UPLC Text type)
            assertTrue(reg.lookupBuilder("com.bloxbean.cardano.julc.stdlib.lib.ContextsLib", "trace").isPresent());
            // Other ContextsLib methods migrated
            assertFalse(reg.contains("com.bloxbean.cardano.julc.stdlib.lib.ContextsLib", "getTxInfo"));
            assertFalse(reg.contains("com.bloxbean.cardano.julc.stdlib.lib.ContextsLib", "signedBy"));
        }

        @Test
        void lookupMigratedIntervalReturnsEmpty() {
            var reg = StdlibRegistry.defaultRegistry();
            // IntervalLib methods are now compiled from Java source, not in registry
            assertFalse(reg.contains("com.bloxbean.cardano.julc.stdlib.lib.IntervalLib", "always"));
        }

        @Test
        void lookupBuiltinViaRegistry() {
            var reg = StdlibRegistry.defaultRegistry();
            var input = new PirTerm.Const(Constant.byteString(new byte[]{1, 2, 3}));
            var term = reg.lookup("com.bloxbean.cardano.julc.stdlib.Builtins", "sha2_256", List.of(input));
            assertTrue(term.isPresent());
            var result = evalPir(term.get());
            assertTrue(result.isSuccess());
        }
    }
}
