package com.bloxbean.julc.cli.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers @Test-annotated static methods in test/ source files.
 */
public final class TestDiscovery {

    private static final Pattern TEST_ANNOTATION = Pattern.compile("@Test\\s");
    private static final Pattern STATIC_METHOD = Pattern.compile(
            "public\\s+static\\s+boolean\\s+(\\w+)\\s*\\(");
    private static final Pattern CLASS_NAME = Pattern.compile(
            "(?:public\\s+)?class\\s+(\\w+)");

    private TestDiscovery() {}

    public record TestMethod(String sourceFile, String className, String methodName, String source) {}

    /**
     * Scan a directory for test methods.
     * A test method is a static boolean method annotated with @Test.
     */
    public static List<TestMethod> discover(Path testDir) throws IOException {
        var results = new ArrayList<TestMethod>();

        if (!Files.isDirectory(testDir)) {
            return results;
        }

        try (Stream<Path> paths = Files.walk(testDir)) {
            paths.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            String source = Files.readString(p);
                            if (!TEST_ANNOTATION.matcher(source).find()) return;

                            String className = extractClassName(source);
                            if (className == null) return;

                            // Find @Test annotated methods
                            var lines = source.split("\n");
                            for (int i = 0; i < lines.length; i++) {
                                if (lines[i].strip().startsWith("@Test")) {
                                    // Look ahead for the method declaration
                                    for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                                        var matcher = STATIC_METHOD.matcher(lines[j]);
                                        if (matcher.find()) {
                                            results.add(new TestMethod(
                                                    p.toString(), className, matcher.group(1), source));
                                            break;
                                        }
                                    }
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read " + p, e);
                        }
                    });
        }

        return results;
    }

    private static String extractClassName(String source) {
        var matcher = CLASS_NAME.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }
}
