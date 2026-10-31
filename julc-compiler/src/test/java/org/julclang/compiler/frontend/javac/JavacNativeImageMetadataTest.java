package org.julclang.compiler.frontend.javac;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 I6: the one native-image metadata file that names JDK-internal classes, checked against the JDK this build
 * runs on (the JDK the images are built with): every reflected type and method exists, and every resource bundle
 * is a class of that JDK.
 */
class JavacNativeImageMetadataTest {

    static final String FILE = "/META-INF/native-image/org.julclang/julc-compiler/reachability-metadata.json";
    static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"(\\s*,\\s*\"methods\"\\s*:\\s*\\[(.*?)\\]\\s*}\\s*\\])?",
            Pattern.DOTALL);
    static final Pattern METHOD = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"parameterTypes\"\\s*:\\s*\\[([^\\]]*)\\]");
    static final Pattern BUNDLE = Pattern.compile("\"bundle\"\\s*:\\s*\"([^\"]+)\"");

    static String metadata() throws Exception {
        try (InputStream in = JavacNativeImageMetadataTest.class.getResourceAsStream(FILE)) {
            assertNotNull(in, FILE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, ClassLoader.getSystemClassLoader());
    }

    @Test
    void everyEntryExistsInThisJdk() throws Exception {
        String json = metadata();
        var types = new ArrayList<String>();
        Matcher t = TYPE.matcher(json);
        while (t.find()) {
            Class<?> type = load(t.group(1));
            types.add(type.getName());
            if (t.group(3) == null) continue;
            Matcher m = METHOD.matcher(t.group(3));
            while (m.find()) {
                var parameters = new ArrayList<Class<?>>();
                for (String p : m.group(2).split(",")) {
                    if (!p.isBlank()) parameters.add(load(p.strip().replace("\"", "")));
                }
                assertNotNull(type.getDeclaredMethod(m.group(1), parameters.toArray(Class<?>[]::new)));
            }
        }
        assertTrue(types.contains("com.sun.tools.javac.api.JavacTrees"), types.toString());
        var bundles = new ArrayList<String>();
        Matcher b = BUNDLE.matcher(json);
        while (b.find()) {
            assertTrue(java.util.ResourceBundle.class.isAssignableFrom(load(b.group(1))), b.group(1));
            bundles.add(b.group(1));
        }
        assertEquals(List.of("com.sun.tools.javac.resources.compiler", "com.sun.tools.javac.resources.javac"), bundles);
    }

    /** I6: no code references com.sun.tools.javac, and no build file exports or opens a JDK package. */
    @Test
    void noCodeOrBuildReachesIntoJdkInternals() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("src/main/java"))) return; // not run from the module directory
        Path repo = root.getParent();
        var offenders = new ArrayList<String>();
        Files.walkFileTree(repo, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return Set.of("build", "adr", "node_modules", "out").contains(name) || name.startsWith(".")
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws java.io.IOException {
                String rel = repo.relativize(f).toString();
                boolean java = rel.endsWith(".java") && rel.contains("/src/main/");
                boolean build = rel.endsWith(".gradle") || rel.endsWith("native-image.properties");
                if (java || build) {
                    String text = Files.readString(f);
                    if (java && text.contains("com.sun.tools.javac")) offenders.add(rel);
                    if (build && (text.contains("--add-exports") || text.contains("--add-opens"))) offenders.add(rel);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertEquals(List.of(), offenders);
    }
}
