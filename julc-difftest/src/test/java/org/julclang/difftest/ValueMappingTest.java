package org.julclang.difftest;

import org.julclang.core.Constant;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.types.JulcList;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the harness's value mapping (ADR-063 D4) against hand-written Data. */
class ValueMappingTest {

    private static final PlutusData TRUE = new PlutusData.ConstrData(1, List.of());
    private static final PlutusData FALSE = new PlutusData.ConstrData(0, List.of());

    @SuppressWarnings("unused")
    private static void signatures(JulcList<BigInteger> ints, Optional<byte[]> maybe, JulcList<JulcList<Long>> nested) {
    }

    private static Type param(int index) throws NoSuchMethodException {
        return ValueMappingTest.class.getDeclaredMethod("signatures", JulcList.class, Optional.class, JulcList.class)
                .getGenericParameterTypes()[index];
    }

    @Test
    void javaValuesToData() {
        assertEquals(new PlutusData.IntData(BigInteger.valueOf(-5)), ValueMapping.toData(BigInteger.valueOf(-5)));
        assertEquals(new PlutusData.IntData(BigInteger.valueOf(Long.MIN_VALUE)), ValueMapping.toData(Long.MIN_VALUE));
        assertEquals(new PlutusData.IntData(BigInteger.valueOf(7)), ValueMapping.toData(7));
        assertEquals(TRUE, ValueMapping.toData(true));
        assertEquals(FALSE, ValueMapping.toData(false));
        assertEquals(new PlutusData.BytesData(new byte[]{1, 2}), ValueMapping.toData(new byte[]{1, 2}));
        assertEquals(new PlutusData.BytesData(new byte[]{(byte) 0xc3, (byte) 0xa9}), ValueMapping.toData("é"));
        assertEquals(new PlutusData.ConstrData(0, List.of(new PlutusData.IntData(BigInteger.ONE))),
                ValueMapping.toData(Optional.of(BigInteger.ONE)));
        assertEquals(new PlutusData.ConstrData(1, List.of()), ValueMapping.toData(Optional.empty()));
        assertEquals(new PlutusData.ListData(List.of(new PlutusData.IntData(BigInteger.ONE), TRUE)),
                ValueMapping.toData(JulcList.of(BigInteger.ONE, true)));
        assertThrows(HarnessException.class, () -> ValueMapping.toData(null));
        assertThrows(HarnessException.class, () -> ValueMapping.toData(1.5));
    }

    @Test
    void dataToParameterTypes() throws Exception {
        var three = new PlutusData.IntData(BigInteger.valueOf(3));
        assertEquals(BigInteger.valueOf(3), ValueMapping.fromData(three, BigInteger.class));
        assertEquals(3L, ValueMapping.fromData(three, long.class));
        assertEquals(3, ValueMapping.fromData(three, int.class));
        assertEquals(true, ValueMapping.fromData(TRUE, boolean.class));
        assertEquals(false, ValueMapping.fromData(FALSE, boolean.class));
        assertArrayEquals(new byte[]{9}, (byte[]) ValueMapping.fromData(new PlutusData.BytesData(new byte[]{9}), byte[].class));
        assertEquals(JulcList.of(BigInteger.ONE, BigInteger.TWO), ValueMapping.fromData(new PlutusData.ListData(
                List.of(new PlutusData.IntData(BigInteger.ONE), new PlutusData.IntData(BigInteger.TWO))), param(0)));
        var some = (Optional<?>) ValueMapping.fromData(new PlutusData.ConstrData(0, List.of(new PlutusData.BytesData(new byte[]{4}))), param(1));
        assertArrayEquals(new byte[]{4}, (byte[]) some.orElseThrow());
        assertEquals(JulcList.of(JulcList.of(5L)), ValueMapping.fromData(new PlutusData.ListData(List.of(
                new PlutusData.ListData(List.of(new PlutusData.IntData(BigInteger.valueOf(5)))))), param(2)));
    }

    @Test
    void malformedArgumentsAreHarnessErrors() {
        var big = new PlutusData.IntData(BigInteger.ONE.shiftLeft(64));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(big, long.class));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(new PlutusData.ConstrData(2, List.of()), boolean.class));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(TRUE, BigInteger.class));
        assertThrows(HarnessException.class, () -> ValueMapping.fromData(TRUE, Double.class));
    }

    @Test
    void resultTermsToData() {
        assertEquals(new PlutusData.IntData(BigInteger.TEN), ValueMapping.fromTerm(new Term.Const(Constant.integer(BigInteger.TEN))));
        assertEquals(TRUE, ValueMapping.fromTerm(new Term.Const(Constant.bool(true))));
        assertEquals(new PlutusData.BytesData(new byte[]{1}), ValueMapping.fromTerm(new Term.Const(new Constant.ByteStringConst(new byte[]{1}))));
        assertEquals(new PlutusData.BytesData(new byte[]{'a'}), ValueMapping.fromTerm(new Term.Const(new Constant.StringConst("a"))));
        var data = new PlutusData.ConstrData(3, List.of(new PlutusData.IntData(BigInteger.ONE)));
        assertEquals(data, ValueMapping.fromTerm(new Term.Const(new Constant.DataConst(data))));
        assertEquals(new PlutusData.ListData(List.of(data)), ValueMapping.fromTerm(new Term.Const(
                new Constant.ListConst(DefaultUni.DATA, List.of(new Constant.DataConst(data))))));
        assertEquals(new PlutusData.ConstrData(1, List.of(new PlutusData.IntData(BigInteger.TWO))),
                ValueMapping.fromTerm(new Term.Constr(1, List.of(new Term.Const(Constant.integer(BigInteger.TWO))))));
        assertThrows(HarnessException.class, () -> ValueMapping.fromTerm(new Term.Const(new Constant.UnitConst())));
    }
}
