package org.julclang.verification;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The resolvers' reading of a source, on javac's parser (ADR-068 M6), including the shapes
 * the two trees hold differently: a minus folded into a literal, a text block, empty parentheses, a single member.
 */
class SourceAnnotationsTest {

    private static final String SOURCE = """
            package com.example;

            import org.julclang.verification.annotation.*;
            import static java.util.Objects.requireNonNull;
            import org.julclang.verification.annotation.MintAction;

            // a comment
            @org.julclang.verification.annotation.RequiresSigner("datum.owner")
            @ControlledMint(authority = "AB" + "CD", tokenName = \"""
                text\""", quantity = -5L, action = MintAction . MINT)
            @Monotonic(current = "datum.n", next = "redeemer.n", relation = Relation.GREATER_THAN, max = 0x10, big = 1_000L)
            @PreservesValue()
            @Marker
            \tpublic class V {
                @Ignored int field;
            }
            record Other() {}
            """;

    @Test
    void theUnitIsRead() {
        var javac = SourceAnnotations.read(SOURCE);
    }

    @Test
    void theValuesAreRead() {
        var unit = SourceAnnotations.read(SOURCE);
        assertEquals("com.example", unit.packageName());
        assertEquals(3, unit.imports().size());
        var type = unit.types().getFirst();
        var signer = type.annotations().get(0);
        assertEquals("datum.owner", signer.single().string());
        assertEquals("RequiresSigner", signer.identifier());
        var mint = type.annotations().get(1);
        assertNull(mint.members().get(0).value().string(), "a + of literals is not a literal");
        assertNull(mint.members().get(1).value().string(), "JavaParser does not read a text block as a string literal");
        assertNull(mint.members().get(2).value().integer(), "a minus is an operator, not part of the literal");
        assertEquals("MintAction.MINT", mint.members().get(3).value().text());
        var monotonic = type.annotations().get(2);
        assertEquals(16L, monotonic.members().get(3).value().integer());
        assertEquals(1000L, monotonic.members().get(4).value().integer());
        assertEquals("1_000L", monotonic.members().get(4).value().text());
        assertEquals(true, type.annotations().get(3).normal(), "@A() has named members, none");
        assertEquals(false, type.annotations().get(4).normal());
        assertEquals("@PreservesValue()", type.annotations().get(3).text());
    }

    @Test
    void anUnreadableSourceThrows() {
        assertThrows(RuntimeException.class,
                () -> SourceAnnotations.read("class {"));
    }

    @Test
    void aCommentBeforeAValueAndASpacedMinusPrintInTheirFormat() {
        String source = """
                package p;
                import org.julclang.verification.annotation.*;
                @RequiresSigner(/* who */ "datum.owner")
                @ControlledMint(policy = "abcd", tokenName = /* name */ "ab", amount = - 5)
                class V1 {}
                """;
        var javac = SourceAnnotations.read(source);
        assertTrue(javac.types().getFirst().annotations().get(0).text().contains("\"datum.owner\""));
        assertTrue(javac.types().getFirst().annotations().get(1).text().contains("-5"));
        assertEquals("datum.owner", javac.types().getFirst().annotations().getFirst().single().string());
    }
}
