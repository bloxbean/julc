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
 * Every {@link TestDiscoveryTest} case read by javac's parser (ADR-068 M6): the same methods, or the same failure, as
 * JavaParser's reading. The one case where they differ is overridden, with the reason.
 */
class TestDiscoveryOnJavacTest extends TestDiscoveryTest {

    @Override
    List<TestDiscovery.TestMethod> discover(Path dir) throws IOException {
        List<TestDiscovery.TestMethod> expected;
        try {
            expected = TestDiscovery.discover(dir, TestDiscovery.Reader.JAVAPARSER);
        } catch (IllegalArgumentException e) {
            var actual = assertThrows(IllegalArgumentException.class,
                    () -> TestDiscovery.discover(dir, TestDiscovery.Reader.JAVAC));
            assertEquals(prefix(e.getMessage()), prefix(actual.getMessage()));
            throw actual;
        }
        var actual = TestDiscovery.discover(dir, TestDiscovery.Reader.JAVAC);
        assertEquals(expected, actual);
        return actual;
    }

    /** The message up to the problems, which are each parser's own words. */
    private static String prefix(String message) {
        return message.substring(0, message.indexOf(": "));
    }

    /**
     * javac reads a literal with an unpaired surrogate: it is valid Java. JuLC rejects it when it compiles the test
     * ({@code JULC0093} on the javac frontend, the reader's error on the JavaParser one), so the test is found, and then
     * fails with that diagnostic, where JavaParser's strict reading stopped {@code julc check} before any test ran.
     */
    @Override
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
        assertThrows(IllegalArgumentException.class,
                () -> TestDiscovery.discover(tempDir, TestDiscovery.Reader.JAVAPARSER));
        var found = TestDiscovery.discover(tempDir, TestDiscovery.Reader.JAVAC);
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
}
