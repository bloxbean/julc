package org.julclang.core;

import org.julclang.core.Constant.ArrayConst;
import org.julclang.core.Constant.ListConst;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The pinned array semantics shared by the VM builtins and the ADR-046 literal fold. */
class ArraySemanticsTest {

    private static final DefaultUni INTEGER = new DefaultUni.Integer();
    private static final ArrayConst THREE = new ArrayConst(INTEGER, List.of(
            Constant.integer(10), Constant.integer(20), Constant.integer(30)));

    @Test
    void lengthAndConversionAreTotalAndKeepTheElementUniverse() {
        assertEquals(BigInteger.valueOf(3), ArraySemantics.lengthOfArray(THREE));
        assertEquals(BigInteger.ZERO, ArraySemantics.lengthOfArray(new ArrayConst(INTEGER, List.of())));
        var list = new ListConst(new DefaultUni.Data(), List.of(Constant.data(PlutusData.integer(1)), Constant.data(PlutusData.bytes(new byte[]{2}))));
        var array = ArraySemantics.listToArray(list);
        assertEquals(list.elemType(), array.elemType());
        assertEquals(list.values(), array.values());
        assertEquals(new ArrayConst(INTEGER, List.of()), ArraySemantics.listToArray(new ListConst(INTEGER, List.of())));
    }

    @Test
    void indexReturnsTheElementInsideTheBoundsAndFailsWithThePinnedTextOutside() {
        assertEquals(Constant.integer(10), ArraySemantics.indexArray(THREE, BigInteger.ZERO));
        assertEquals(Constant.integer(30), ArraySemantics.indexArray(THREE, BigInteger.TWO));
        assertEquals("IndexArray: index 3 out of bounds for array of size 3",
                assertThrows(ArraySemantics.EvaluationFailure.class, () -> ArraySemantics.indexArray(THREE, BigInteger.valueOf(3))).getMessage());
        assertEquals("IndexArray: index -1 out of bounds for array of size 3",
                assertThrows(ArraySemantics.EvaluationFailure.class, () -> ArraySemantics.indexArray(THREE, BigInteger.valueOf(-1))).getMessage());
        assertEquals("IndexArray: index 0 out of bounds for array of size 0",
                assertThrows(ArraySemantics.EvaluationFailure.class,
                        () -> ArraySemantics.indexArray(new ArrayConst(INTEGER, List.of()), BigInteger.ZERO)).getMessage());
        var huge = BigInteger.ONE.shiftLeft(63);
        assertEquals("IndexArray: index out of range: " + huge,
                assertThrows(ArraySemantics.EvaluationFailure.class, () -> ArraySemantics.indexArray(THREE, huge)).getMessage());
        assertEquals("IndexArray: index out of range: " + huge.negate(),
                assertThrows(ArraySemantics.EvaluationFailure.class, () -> ArraySemantics.indexArray(THREE, huge.negate())).getMessage());
    }
}
