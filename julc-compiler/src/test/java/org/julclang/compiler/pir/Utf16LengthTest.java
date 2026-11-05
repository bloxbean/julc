package org.julclang.compiler.pir;

import org.julclang.core.Constant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class Utf16LengthTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "é", "€", "😀", "a😀é€"})
    void aConstantFoldsToJavasValue(String value) {
        var folded = PirHelpers.utf16Length(new PirTerm.Const(Constant.string(value)));
        assertEquals(new PirTerm.Const(Constant.integer(BigInteger.valueOf(value.length()))), folded);
    }

    @Test
    void aVariableIsCountedOnChain() {
        var term = PirHelpers.utf16Length(new PirTerm.Var("s", new PirType.StringType()));
        var bytes = assertInstanceOf(PirTerm.Let.class, term);
        assertEquals("#utf8", bytes.name());
    }
}
