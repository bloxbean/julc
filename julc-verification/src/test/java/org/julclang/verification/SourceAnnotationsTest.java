package org.julclang.verification;

import org.julclang.compiler.frontend.javac.SourceReader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The resolvers' reading of a source is the same on JavaParser's and javac's parser (ADR-068 M6), including the shapes
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
    void bothReadersReadTheSameUnit() {
        var javaParser = SourceAnnotations.read(SOURCE, SourceReader.JAVAPARSER);
        var javac = SourceAnnotations.read(SOURCE, SourceReader.JAVAC);
        assertEquals(javaParser, javac);
    }

    @Test
    void theValuesAreJavaParsersReading() {
        var unit = SourceAnnotations.read(SOURCE, SourceReader.JAVAC);
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
    void anUnreadableSourceThrowsOnBoth() {
        assertThrows(RuntimeException.class,
                () -> SourceAnnotations.read("class {", SourceReader.JAVAPARSER));
        assertThrows(RuntimeException.class,
                () -> SourceAnnotations.read("class {", SourceReader.JAVAC));
    }

    @Test
    void aCommentBeforeAValueAndASpacedMinusPrintAsJavaParserPrintsThem() {
        String source = """
                package p;
                import org.julclang.verification.annotation.*;
                @RequiresSigner(/* who */ "datum.owner")
                @ControlledMint(policy = "abcd", tokenName = /* name */ "ab", amount = - 5)
                class V1 {}
                """;
        var javaParser = SourceAnnotations.read(source, SourceReader.JAVAPARSER);
        var javac = SourceAnnotations.read(source, SourceReader.JAVAC);
        for (int i = 0; i < 2; i++) {
            assertEquals(javaParser.types().getFirst().annotations().get(i).text(),
                    javac.types().getFirst().annotations().get(i).text());
        }
        assertEquals("datum.owner", javac.types().getFirst().annotations().getFirst().single().string());
    }
}
