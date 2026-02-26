package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TermExtractorTest {

    @Nested
    class ExtractInteger {

        @Test
        void fromIntegerConst() {
            var term = new Term.Const(new Constant.IntegerConst(BigInteger.valueOf(42)));
            assertEquals(BigInteger.valueOf(42), TermExtractor.extractInteger(term));
        }

        @Test
        void fromDataIntData() {
            var term = new Term.Const(new Constant.DataConst(PlutusData.integer(99)));
            assertEquals(BigInteger.valueOf(99), TermExtractor.extractInteger(term));
        }

        @Test
        void throwsForNonInteger() {
            var term = new Term.Const(new Constant.BoolConst(true));
            assertThrows(TermExtractor.ExtractionException.class,
                    () -> TermExtractor.extractInteger(term));
        }
    }

    @Nested
    class ExtractByteString {

        @Test
        void fromByteStringConst() {
            var data = new byte[]{1, 2, 3};
            var term = new Term.Const(new Constant.ByteStringConst(data));
            assertArrayEquals(data, TermExtractor.extractByteString(term));
        }

        @Test
        void fromDataBytesData() {
            var data = new byte[]{10, 20};
            var term = new Term.Const(new Constant.DataConst(PlutusData.bytes(data)));
            assertArrayEquals(data, TermExtractor.extractByteString(term));
        }

        @Test
        void throwsForNonBytes() {
            var term = new Term.Const(new Constant.IntegerConst(BigInteger.ONE));
            assertThrows(TermExtractor.ExtractionException.class,
                    () -> TermExtractor.extractByteString(term));
        }
    }

    @Nested
    class ExtractBoolean {

        @Test
        void fromBoolConstTrue() {
            var term = new Term.Const(new Constant.BoolConst(true));
            assertTrue(TermExtractor.extractBoolean(term));
        }

        @Test
        void fromBoolConstFalse() {
            var term = new Term.Const(new Constant.BoolConst(false));
            assertFalse(TermExtractor.extractBoolean(term));
        }

        @Test
        void fromConstrDataTrue() {
            // Plutus Bool: True = Constr(1, [])
            var term = new Term.Const(new Constant.DataConst(PlutusData.constr(1)));
            assertTrue(TermExtractor.extractBoolean(term));
        }

        @Test
        void fromConstrDataFalse() {
            // Plutus Bool: False = Constr(0, [])
            var term = new Term.Const(new Constant.DataConst(PlutusData.constr(0)));
            assertFalse(TermExtractor.extractBoolean(term));
        }

        @Test
        void fromV3ConstrTermTrue() {
            var term = new Term.Constr(1, List.of());
            assertTrue(TermExtractor.extractBoolean(term));
        }

        @Test
        void fromV3ConstrTermFalse() {
            var term = new Term.Constr(0, List.of());
            assertFalse(TermExtractor.extractBoolean(term));
        }

        @Test
        void throwsForNonBoolean() {
            var term = new Term.Const(new Constant.IntegerConst(BigInteger.ONE));
            assertThrows(TermExtractor.ExtractionException.class,
                    () -> TermExtractor.extractBoolean(term));
        }
    }

    @Nested
    class ExtractString {

        @Test
        void fromStringConst() {
            var term = new Term.Const(new Constant.StringConst("hello"));
            assertEquals("hello", TermExtractor.extractString(term));
        }

        @Test
        void throwsForNonString() {
            var term = new Term.Const(new Constant.IntegerConst(BigInteger.ONE));
            assertThrows(TermExtractor.ExtractionException.class,
                    () -> TermExtractor.extractString(term));
        }
    }

    @Nested
    class ExtractData {

        @Test
        void fromDataConst() {
            var data = PlutusData.integer(42);
            var term = new Term.Const(new Constant.DataConst(data));
            assertEquals(data, TermExtractor.extractData(term));
        }

        @Test
        void fromIntegerConst() {
            var term = new Term.Const(new Constant.IntegerConst(BigInteger.valueOf(7)));
            assertEquals(PlutusData.integer(7), TermExtractor.extractData(term));
        }

        @Test
        void fromByteStringConst() {
            var term = new Term.Const(new Constant.ByteStringConst(new byte[]{1, 2}));
            var result = TermExtractor.extractData(term);
            assertInstanceOf(PlutusData.BytesData.class, result);
        }

        @Test
        void fromBoolConst() {
            var term = new Term.Const(new Constant.BoolConst(true));
            var result = TermExtractor.extractData(term);
            assertInstanceOf(PlutusData.ConstrData.class, result);
            assertEquals(1, ((PlutusData.ConstrData) result).tag());
        }

        @Test
        void fromV3ConstrTerm() {
            var inner = new Term.Const(new Constant.IntegerConst(BigInteger.valueOf(5)));
            var term = new Term.Constr(0, List.of(inner));
            var result = TermExtractor.extractData(term);
            assertInstanceOf(PlutusData.ConstrData.class, result);
            var constr = (PlutusData.ConstrData) result;
            assertEquals(0, constr.tag());
            assertEquals(1, constr.fields().size());
        }
    }

    @Nested
    class ExtractOptional {

        @Test
        void someFromDataConst() {
            // Some(42) = ConstrData(0, [IntData(42)])
            var data = new PlutusData.ConstrData(0, List.of(PlutusData.integer(42)));
            var term = new Term.Const(new Constant.DataConst(data));
            var result = TermExtractor.extractOptional(term);
            assertTrue(result.isPresent());
            assertEquals(PlutusData.integer(42), result.get());
        }

        @Test
        void noneFromDataConst() {
            // None = ConstrData(1, [])
            var data = PlutusData.constr(1);
            var term = new Term.Const(new Constant.DataConst(data));
            var result = TermExtractor.extractOptional(term);
            assertTrue(result.isEmpty());
        }

        @Test
        void someFromV3Constr() {
            var inner = new Term.Const(new Constant.IntegerConst(BigInteger.valueOf(7)));
            var term = new Term.Constr(0, List.of(inner));
            var result = TermExtractor.extractOptional(term);
            assertTrue(result.isPresent());
        }

        @Test
        void noneFromV3Constr() {
            var term = new Term.Constr(1, List.of());
            var result = TermExtractor.extractOptional(term);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class ExtractList {

        @Test
        void fromListData() {
            var data = new PlutusData.ListData(List.of(
                    PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)));
            var term = new Term.Const(new Constant.DataConst(data));
            var result = TermExtractor.extractList(term);
            assertEquals(3, result.size());
            assertEquals(PlutusData.integer(1), result.get(0));
        }

        @Test
        void fromListConst() {
            var elems = List.<Constant>of(
                    new Constant.DataConst(PlutusData.integer(10)),
                    new Constant.DataConst(PlutusData.integer(20)));
            var term = new Term.Const(new Constant.ListConst(
                    com.bloxbean.cardano.julc.core.DefaultUni.DATA, elems));
            var result = TermExtractor.extractList(term);
            assertEquals(2, result.size());
        }
    }

    @Nested
    class ExtractAuto {

        @Test
        void autoDetectsInteger() {
            var term = new Term.Const(new Constant.IntegerConst(BigInteger.valueOf(42)));
            var result = TermExtractor.extract(term);
            assertInstanceOf(BigInteger.class, result);
            assertEquals(BigInteger.valueOf(42), result);
        }

        @Test
        void autoDetectsBool() {
            var term = new Term.Const(new Constant.BoolConst(true));
            assertEquals(true, TermExtractor.extract(term));
        }

        @Test
        void autoDetectsString() {
            var term = new Term.Const(new Constant.StringConst("hello"));
            assertEquals("hello", TermExtractor.extract(term));
        }

        @Test
        void autoDetectsUnit() {
            var term = new Term.Const(new Constant.UnitConst());
            assertNull(TermExtractor.extract(term));
        }

        @Test
        void autoDetectsData() {
            var data = PlutusData.integer(99);
            var term = new Term.Const(new Constant.DataConst(data));
            assertEquals(data, TermExtractor.extract(term));
        }
    }

    @Nested
    class ExtractResultTerm {

        @Test
        void fromSuccess() {
            var term = new Term.Const(new Constant.IntegerConst(BigInteger.ONE));
            var result = new EvalResult.Success(term, ExBudget.ZERO, List.of());
            assertEquals(term, TermExtractor.extractResultTerm(result));
        }

        @Test
        void throwsForFailure() {
            var result = new EvalResult.Failure("error msg", ExBudget.ZERO, List.of());
            var ex = assertThrows(TermExtractor.ExtractionException.class,
                    () -> TermExtractor.extractResultTerm(result));
            assertTrue(ex.getMessage().contains("error msg"));
        }

        @Test
        void throwsForBudgetExhausted() {
            var result = new EvalResult.BudgetExhausted(ExBudget.ZERO, List.of());
            assertThrows(TermExtractor.ExtractionException.class,
                    () -> TermExtractor.extractResultTerm(result));
        }
    }
}
