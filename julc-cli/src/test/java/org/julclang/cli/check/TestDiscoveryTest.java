package org.julclang.cli.check;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestDiscoveryTest {

    @Test
    void discoverFindsTestMethods(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("MyTest.java"), """
                import org.julclang.stdlib.test.Test;

                public class MyTest {

                    @Test
                    public static boolean test_one() { return true; }

                    @Test
                    public static boolean test_two() { return false; }

                    public static boolean not_a_test() { return true; }
                }
                """);

        var tests = TestDiscovery.discover(tempDir);
        assertEquals(2, tests.size());
        assertEquals("test_one", tests.get(0).methodName());
        assertEquals("test_two", tests.get(1).methodName());
        assertEquals("MyTest", tests.get(0).className());
    }

    @Test
    void discoverSkipsSourcesThatOnlyMentionTestAndJuLCCannotRead(@TempDir Path tempDir) throws IOException {
        // ADR-062 T24: the Javadoc mention makes it a candidate, and JuLC's strict reading rejects the unpaired
        // surrogate, but read as javac reads it the source has no Test method, so it is skipped.
        Files.writeString(tempDir.resolve("Mentions.java"), """
                import java.util.regex.Pattern;

                /** Not a @Test source. */
                public class Mentions {
                    static final Pattern HIGH = Pattern.compile("[\\uD800-\\uDBFF]");
                }
                """);
        assertEquals(0, TestDiscovery.discover(tempDir).size());

        // With a Test method, the strict diagnostic stands.
        Files.writeString(tempDir.resolve("Mentions.java"), """
                public class Mentions {
                    @Test
                    public static boolean t() { return "\\uD800".isEmpty(); }
                }
                """);
        var error = assertThrows(IllegalArgumentException.class, () -> TestDiscovery.discover(tempDir));
        assertTrue(error.getMessage().contains("Could not parse @Test candidate"), error.getMessage());
    }

    @Test
    void discoverSkipsFilesWithoutTestAnnotation(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("NoTests.java"), """
                public class NoTests {
                    public static boolean helper() { return true; }
                }
                """);

        var tests = TestDiscovery.discover(tempDir);
        assertTrue(tests.isEmpty());
    }

    @Test
    void discoverIgnoresTestAnnotationTextInCommentsAndStrings(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("CommentOnly.java"), """
                public class CommentOnly {
                    // @Test should not be discovered from a comment.
                    static final String DOC = "@Test";
                    public static boolean helper() { return true; }
                }
                """);

        var tests = TestDiscovery.discover(tempDir);

        assertTrue(tests.isEmpty());
    }

    @Test
    void discoverReportsParseErrorForPrefilteredTestCandidate(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("BrokenTest.java"), """
                // @Test
                class BrokenTest {
                """);

        var ex = assertThrows(IllegalArgumentException.class, () -> TestDiscovery.discover(tempDir));

        assertTrue(ex.getMessage().contains("Could not parse @Test candidate"));
        assertTrue(ex.getMessage().contains("BrokenTest.java"));
    }

    @Test
    void discoverEmptyDirectory(@TempDir Path tempDir) throws IOException {
        var tests = TestDiscovery.discover(tempDir);
        assertTrue(tests.isEmpty());
    }

    @Test
    void discoverNonexistentDirectory() throws IOException {
        var tests = TestDiscovery.discover(Path.of("/nonexistent/dir"));
        assertTrue(tests.isEmpty());
    }
}
