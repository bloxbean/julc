package org.julclang.vm.java.builtins;

import org.julclang.core.Constant;
import org.julclang.vm.java.CekValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class BitwiseBuiltinsTest {

    @Test
    void integerToByteStringRejectsWidthThatWouldOverflowInt() {
        var args = List.<CekValue>of(
                new CekValue.VCon(Constant.bool(true)),
                new CekValue.VCon(Constant.integer(BigInteger.ONE.shiftLeft(32))),
                new CekValue.VCon(Constant.integer(BigInteger.ZERO)));

        assertThrows(BuiltinException.class,
                () -> BitwiseBuiltins.integerToByteString(args));
    }

    @Test
    void integerToByteStringRejectsHugeNegativeWidth() {
        var args = List.<CekValue>of(
                new CekValue.VCon(Constant.bool(true)),
                new CekValue.VCon(Constant.integer(
                        BigInteger.ONE.shiftLeft(64).negate())),
                new CekValue.VCon(Constant.integer(BigInteger.ZERO)));

        assertThrows(BuiltinException.class,
                () -> BitwiseBuiltins.integerToByteString(args));
    }
}
