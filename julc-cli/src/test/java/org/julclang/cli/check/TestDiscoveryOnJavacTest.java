package org.julclang.cli.check;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What javac's parser reads of a test source: a literal with an unpaired surrogate is valid Java, later Java is read, and
 * nested types come in source order (ADR-068 M6).
 */
class TestDiscoveryOnJavacTest {

    private static List<TestDiscovery.TestMethod> discover(Path dir) throws IOException {
        return TestDiscovery.discover(dir);
    }

    /**
     * javac reads a literal with an unpaired surrogate: it is valid Java. JuLC rejects it when it compiles the test
     * ({@code JULC0093} on the javac frontend, the reader's error on the JavaParser one), so the test is found, and then
     * fails with that diagnostic, before any test ran.
     */
    @Test
    void discoverSkipsSourcesThatOnlyMentionTestAndJuLCCannotRead(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("Mentions.java"), """
                import java.util.regex.Pattern;

                /** Not a @Test source. */
                public class Mentions {
                    static final Pattern HIGH = Pattern.compile("[\\uD800-\\uDBFF]");
                }
                """);
        assertEquals(0, discover(tempDir).size());

        Files.writeString(tempDir.resolve("Mentions.java"), """
                public class Mentions {
                    @Test
                    public static boolean t() { return "\\uD800".isEmpty(); }
                }
                """);
        var found = TestDiscovery.discover(tempDir);
        assertEquals(1, found.size());
        assertEquals("t", found.getFirst().methodName());
    }

    @Test
    void findsWhatJavaParserFindsInNestedLocalAndQualifiedShapes(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("Shapes.java"), """
                import org.julclang.stdlib.test.Test;

                public class Shapes {
                    @Test public static boolean a() { return true; }
                    @org.julclang.stdlib.test.Test
                    public static boolean b() { return true; }
                    @Test static boolean notPublic() { return true; }
                    @Test public boolean notStatic() { return true; }
                    @Test public static Boolean boxed() { return true; }
                    @Test public static boolean[] array() { return null; }
                    public Shapes() { }
                    interface Inner { @Test public static boolean c() { return true; } }
                    record R() { @Test public static boolean inRecord() { return true; } }
                    enum E { X; @Test public static boolean inEnum() { return true; } }
                    static Object o = new Object() { @Test public static boolean anonymous() { return true; } };
                    static void m() { class Local { @Test public static boolean d() { return true; } } }
                }
                class Second { @Test public static boolean e() { return true; } }
                """);
        var found = discover(tempDir);
        assertEquals(List.of("a", "b", "c", "d", "e"),
                found.stream().map(TestDiscovery.TestMethod::methodName).toList());
        assertTrue(found.stream().anyMatch(t -> t.className().equals("Local")));
    }

    @Test
    void enumsAreReadInJavaParsersOrder(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("EnumTests.java"), """
                enum EnumTests {
                    A { class InA { @Test public static boolean inA() { return true; } } };
                    static class InMembers { @Test public static boolean inMembers() { return true; } }
                }
                """);
        var found = discover(tempDir);
        assertEquals(List.of("inMembers", "inA"), found.stream().map(TestDiscovery.TestMethod::methodName).toList());
    }

    @Test
    void laterJavaThatOnlyMentionsTestIsSkippedAndOtherErrorsAreReported(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("LaterJavaTest.java"), """
                // mentions @Test
                class LaterJavaTest {
                    static void f(Object o) { if (o instanceof String _) {} }
                }
                """);
        assertEquals(List.of(), TestDiscovery.discover(tempDir));
        Files.writeString(tempDir.resolve("LaterJavaTest.java"), """
                class LaterJavaTest {
                    @Test public static boolean t() { Object o = ""; return o instanceof String _; }
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> TestDiscovery.discover(tempDir));
        Files.writeString(tempDir.resolve("LaterJavaTest.java"), "// @Test\nclass LaterJavaTest {\n");
        assertThrows(IllegalArgumentException.class, () -> TestDiscovery.discover(tempDir));
    }
}
