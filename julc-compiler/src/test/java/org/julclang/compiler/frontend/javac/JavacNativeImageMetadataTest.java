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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 I6: the one native-image metadata file that names JDK-internal classes, checked against the JDK this build
 * runs on (the JDK the images are built with): every reflected type and method exists, and every resource bundle
 * is a class of that JDK.
 */
class JavacNativeImageMetadataTest {

    static final String FILE = "/META-INF/native-image/org.julclang/julc-compiler/reachability-metadata.json";

    static Map<String, Object> metadata() throws Exception {
        try (InputStream in = JavacNativeImageMetadataTest.class.getResourceAsStream(FILE)) {
            assertNotNull(in, FILE);
            @SuppressWarnings("unchecked")
            var json = (Map<String, Object>) new Json(new String(in.readAllBytes(), StandardCharsets.UTF_8)).value();
            return json;
        }
    }

    static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, ClassLoader.getSystemClassLoader());
    }

    /** The checked entries: every reflected type and method, every bundle. Throws on the first that is missing. */
    @SuppressWarnings("unchecked")
    static int[] validate(Map<String, Object> json) throws Exception {
        int types = 0, methods = 0, bundles = 0;
        for (Object o : (List<Object>) json.get("reflection")) {
            var entry = (Map<String, Object>) o;
            Class<?> type = load((String) entry.get("type"));
            types++;
            for (Object m : (List<Object>) entry.getOrDefault("methods", List.of())) {
                var method = (Map<String, Object>) m;
                var parameters = new ArrayList<Class<?>>();
                for (Object p : (List<Object>) method.get("parameterTypes")) parameters.add(load((String) p));
                type.getDeclaredMethod((String) method.get("name"), parameters.toArray(Class<?>[]::new));
                methods++;
            }
        }
        for (Object o : (List<Object>) json.get("resources")) {
            var entry = (Map<String, Object>) o;
            if (entry.containsKey("bundle")) {
                assertTrue(ResourceBundle.class.isAssignableFrom(load((String) entry.get("bundle"))), entry.toString());
                bundles++;
            }
        }
        return new int[]{types, methods, bundles};
    }

    @Test
    void everyEntryExistsInThisJdk() throws Exception {
        assertArrayEquals(new int[]{4, 1, 2}, validate(metadata()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMissingMethodFailsTheCheck() throws Exception {
        var json = metadata();
        var trees = (Map<String, Object>) ((List<Object>) json.get("reflection")).getFirst();
        var instance = (Map<String, Object>) ((List<Object>) trees.get("methods")).getFirst();
        assertEquals("instance", instance.get("name"));
        instance.put("name", "NO_SUCH_METHOD");
        assertThrows(NoSuchMethodException.class, () -> validate(json));
    }

    /** A minimal JSON reader (objects, arrays, strings, numbers, literals) for the metadata file. */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            char c = s.charAt(i);
            if (c == '{') {
                var map = new LinkedHashMap<String, Object>();
                i++;
                skip();
                if (s.charAt(i) == '}') { i++; return map; }
                while (true) {
                    skip();
                    String key = (String) value();
                    skip();
                    expect(':');
                    map.put(key, value());
                    skip();
                    if (s.charAt(i++) == '}') return map;
                }
            }
            if (c == '[') {
                var list = new ArrayList<Object>();
                i++;
                skip();
                if (s.charAt(i) == ']') { i++; return list; }
                while (true) {
                    list.add(value());
                    skip();
                    if (s.charAt(i++) == ']') return list;
                }
            }
            if (c == '"') {
                var out = new StringBuilder();
                i++;
                while (s.charAt(i) != '"') {
                    char ch = s.charAt(i++);
                    if (ch == '\\') {
                        char e = s.charAt(i++);
                        out.append(switch (e) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'u' -> { char u = (char) Integer.parseInt(s.substring(i, i + 4), 16); i += 4; yield u; }
                            default -> e;
                        });
                    } else {
                        out.append(ch);
                    }
                }
                i++;
                return out.toString();
            }
            int start = i;
            while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) i++;
            String literal = s.substring(start, i);
            return switch (literal) {
                case "true" -> true;
                case "false" -> false;
                case "null" -> null;
                default -> Double.parseDouble(literal);
            };
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private void expect(char c) {
            if (s.charAt(i++) != c) throw new IllegalArgumentException("expected " + c + " at " + (i - 1));
        }
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
