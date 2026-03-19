package com.bloxbean.cardano.julc.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultUniTest {

    @Test
    void baseTypeSingletons() {
        assertInstanceOf(DefaultUni.Integer.class, DefaultUni.INTEGER);
        assertInstanceOf(DefaultUni.ByteString.class, DefaultUni.BYTESTRING);
        assertInstanceOf(DefaultUni.String.class, DefaultUni.STRING);
        assertInstanceOf(DefaultUni.Unit.class, DefaultUni.UNIT);
        assertInstanceOf(DefaultUni.Bool.class, DefaultUni.BOOL);
        assertInstanceOf(DefaultUni.Data.class, DefaultUni.DATA);
        assertInstanceOf(DefaultUni.Bls12_381_G1_Element.class, DefaultUni.BLS12_381_G1);
        assertInstanceOf(DefaultUni.Bls12_381_G2_Element.class, DefaultUni.BLS12_381_G2);
        assertInstanceOf(DefaultUni.Bls12_381_MlResult.class, DefaultUni.BLS12_381_ML);
    }

    @Test
    void listOfInteger() {
        var listInt = DefaultUni.listOf(DefaultUni.INTEGER);
        assertInstanceOf(DefaultUni.Apply.class, listInt);
        var apply = (DefaultUni.Apply) listInt;
        assertInstanceOf(DefaultUni.ProtoList.class, apply.f());
        assertEquals(DefaultUni.INTEGER, apply.arg());
    }

    @Test
    void listOfByteString() {
        var listBs = DefaultUni.listOf(DefaultUni.BYTESTRING);
        var apply = (DefaultUni.Apply) listBs;
        assertInstanceOf(DefaultUni.ProtoList.class, apply.f());
        assertEquals(DefaultUni.BYTESTRING, apply.arg());
    }

    @Test
    void pairOfIntegerString() {
        var pair = DefaultUni.pairOf(DefaultUni.INTEGER, DefaultUni.STRING);
        // pairOf(A, B) = Apply(Apply(ProtoPair, A), B)
        assertInstanceOf(DefaultUni.Apply.class, pair);
        var outer = (DefaultUni.Apply) pair;
        assertEquals(DefaultUni.STRING, outer.arg());

        assertInstanceOf(DefaultUni.Apply.class, outer.f());
        var inner = (DefaultUni.Apply) outer.f();
        assertInstanceOf(DefaultUni.ProtoPair.class, inner.f());
        assertEquals(DefaultUni.INTEGER, inner.arg());
    }

    @Test
    void nestedListOfList() {
        // List(List(Integer))
        var listListInt = DefaultUni.listOf(DefaultUni.listOf(DefaultUni.INTEGER));
        var outer = (DefaultUni.Apply) listListInt;
        assertInstanceOf(DefaultUni.ProtoList.class, outer.f());
        assertInstanceOf(DefaultUni.Apply.class, outer.arg()); // inner List(Integer)
    }

    @Test
    void sealedInterfacePatternMatch() {
        DefaultUni type = DefaultUni.INTEGER;
        String name = switch (type) {
            case DefaultUni.Integer _ -> "integer";
            case DefaultUni.ByteString _ -> "bytestring";
            case DefaultUni.String _ -> "string";
            case DefaultUni.Unit _ -> "unit";
            case DefaultUni.Bool _ -> "bool";
            case DefaultUni.Data _ -> "data";
            case DefaultUni.ProtoList _ -> "list";
            case DefaultUni.ProtoPair _ -> "pair";
            case DefaultUni.Bls12_381_G1_Element _ -> "g1";
            case DefaultUni.Bls12_381_G2_Element _ -> "g2";
            case DefaultUni.Bls12_381_MlResult _ -> "ml";
            case DefaultUni.ProtoArray _ -> "array";
            case DefaultUni.ProtoValue _ -> "value";
            case DefaultUni.Apply _ -> "apply";
        };
        assertEquals("integer", name);
    }

    @Test
    void recordEquality() {
        // Records have structural equality
        assertEquals(new DefaultUni.Integer(), new DefaultUni.Integer());
        assertEquals(DefaultUni.listOf(DefaultUni.INTEGER), DefaultUni.listOf(DefaultUni.INTEGER));
        assertNotEquals(DefaultUni.listOf(DefaultUni.INTEGER), DefaultUni.listOf(DefaultUni.STRING));
    }
}
