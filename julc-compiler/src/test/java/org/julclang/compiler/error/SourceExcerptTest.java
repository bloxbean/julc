package org.julclang.compiler.error;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SourceExcerptTest {

    @Test
    void underlinesANameWithItsParentheses() {
        assertEquals("""
                2 |     case A(var v) -> v;
                  |          ^^^^^^^^""", SourceExcerpt.of("first\n    case A(var v) -> v;\n", 2, 10));
    }

    @Test
    void underlinesAPlainNameAStringAndASymbol() {
        assertEquals("1 | a.foo + 1\n  |   ^^^", SourceExcerpt.of("a.foo + 1", 1, 3));
        assertEquals("1 | x(\"a\\\"b\", 2)\n  |   ^^^^^^", SourceExcerpt.of("x(\"a\\\"b\", 2)", 1, 3));
        assertEquals("1 | a + 1\n  |   ^", SourceExcerpt.of("a + 1", 1, 3));
    }

    @Test
    void keepsTabsSoTheCaretLinesUp() {
        assertEquals("1 | \t\tfoo\n  | \t\t^^^", SourceExcerpt.of("\t\tfoo", 1, 3));
    }

    @Test
    void readsEveryLineEnding() {
        assertTrue(SourceExcerpt.of("a\r\nb\rc\nd", 4, 1).startsWith("4 | d"));
    }

    @Test
    void cutsALongLineAroundTheColumn() {
        String line = "x".repeat(300) + " target " + "y".repeat(300);
        String excerpt = SourceExcerpt.of(line, 1, 302);
        assertTrue(excerpt.lines().findFirst().orElseThrow().length() < 120, excerpt);
        assertTrue(excerpt.contains("target"), excerpt);
        var lines = excerpt.lines().toList();
        assertEquals("target", lines.get(0).substring(lines.get(1).indexOf('^'), lines.get(1).indexOf('^') + 6));
    }

    @Test
    void aColumnPastTheEndOfTheLinePointsJustAfterIt() {
        assertEquals("1 | ab\n  |   ^", SourceExcerpt.of("ab", 1, 3));
    }

    @Test
    void anUnknownPositionHasNoExcerpt() {
        assertNull(SourceExcerpt.of(null, 1, 1));
        assertNull(SourceExcerpt.of("a", 0, 1));
        assertNull(SourceExcerpt.of("a", 1, 0));
        assertNull(SourceExcerpt.of("a", 2, 1));
        assertNull(SourceExcerpt.of("a", 1, 9));
        assertNull(SourceExcerpt.of("a\n   \nb", 2, 1));
    }

    @Test
    void aCutDoesNotSplitACharacter() {
        String line = "\uD83D\uDE00".repeat(120) + " target";
        for (int column = 1; column <= line.length(); column += 7) {
            var excerpt = SourceExcerpt.of(line, 1, column);
            assertEquals(excerpt, new String(excerpt.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void underlinesAQualifiedNameAnAnnotationAndATextBlockOpening() {
        assertEquals("1 | case I2.A(var w) ->\n  |      ^^^^^^^^^^^", SourceExcerpt.of("case I2.A(var w) ->", 1, 6));
        assertEquals("1 | @Foo class C\n  | ^^^^", SourceExcerpt.of("@Foo class C", 1, 1));
        assertEquals("1 | x = \"\"\"\n  |     ^^^", SourceExcerpt.of("x = \"\"\"", 1, 5));
    }
}
