package org.julclang.compiler;

import org.julclang.compiler.frontend.javac.JavacSyntax;
import org.julclang.compiler.frontend.javac.SourceReader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-068 M6's equivalence gate for {@link LibrarySourceResolver}'s reading of a source: on every corpus file, javac's
 * parser and JavaParser give the same package, imports and class references (with positions), and the same top-level
 * type name. Run by {@code :julc-compiler:adr068Tools -Padr068.tools.sources=...}.
 */
@Tag("adr068-tools")
class Adr068DiscoveryCorpusTest {

    private static final String REJECTS = "the reading that rejects the source falls back to the regex reading both"
            + " share; ADR-068 keeps javac's reading (D5)";

    private static final Map<String, String> ALLOWED = Map.of(
            "context:javac-rejects", REJECTS,
            "context:reader-rejects", REJECTS,
            "top-level:javac-rejects", REJECTS,
            "top-level:reader-rejects", REJECTS,
            "context:javaparser-drops-commented-qualifier", "JavaParser prints a comment that comes right before a"
                    + " qualifier into the qualifier's text, so it misses that library reference; javac's reading keeps it."
                    + " The old reading was wrong: LibrarySourceResolverTest pins javac's");

    @Test
    void javacReadsTheLibraryReferencesJavaParserReads() throws IOException {
        String sources = System.getProperty("adr068.tools.sources");
        Assumptions.assumeTrue(sources != null && !sources.isBlank(), "-Padr068.tools.sources is not set");
        Path out = Path.of(System.getProperty("adr068.tools.out", "build/adr068-tools"));
        Files.createDirectories(out);
        var files = new ArrayList<Path>();
        for (String dir : sources.split(File.pathSeparator)) {
            try (Stream<Path> walk = Files.walk(Path.of(dir))) {
                walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).sorted().forEach(files::add);
            }
        }
        var counts = new TreeMap<String, Integer>();
        var differences = new ArrayList<String>();
        var outputs = new ArrayList<String>();
        for (Path file : files) {
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            } catch (CharacterCodingException e) {
                continue;
            }
            count(counts, "files");
            boolean javacRejects = JavacSyntax.read(text).hasErrors();
            boolean readerRejects = !javacRejects && !JavaSourceParser.parse(text).isSuccessful();
            String other = javacRejects ? ":javac-rejects" : readerRejects ? ":reader-rejects" : ":DIFFERENT";

            String context = LibrarySourceResolver.describeSourceContext(text, SourceReader.JAVAPARSER);
            String contextOnJavac = LibrarySourceResolver.describeSourceContext(text, SourceReader.JAVAC);
            outputs.add(output("context", sources, file, contextOnJavac));
            if (context.contains("ClassReference[")) count(counts, "files-with-references");
            if (!context.equals(contextOnJavac) && other.equals(":DIFFERENT")
                    && onlyCommentedQualifiersAdded(text, context, contextOnJavac)) {
                count(counts, "context:javaparser-drops-commented-qualifier");
                differences.add("context:javaparser-drops-commented-qualifier\t" + file + "\t" + context + "\t" + contextOnJavac);
            } else {
                compare(counts, differences, file, "context", context, contextOnJavac, other);
            }

            String top = String.valueOf(LibrarySourceResolver.extractTopLevelTypeName(text, SourceReader.JAVAPARSER));
            String topOnJavac = String.valueOf(LibrarySourceResolver.extractTopLevelTypeName(text, SourceReader.JAVAC));
            outputs.add(output("top-level", sources, file, topOnJavac));
            compare(counts, differences, file, "top-level", top, topOnJavac, other);
        }
        var summary = new ArrayList<String>();
        counts.forEach((k, v) -> summary.add(k + "\t" + v));
        Files.write(out.resolve("discovery-summary.tsv"), summary);
        Files.write(out.resolve("discovery-differences.tsv"), differences);
        Files.write(out.resolve("discovery-outputs.tsv"), outputs);
        var unexplained = counts.keySet().stream()
                .filter(k -> k.contains(":") && !k.endsWith(":same") && !ALLOWED.containsKey(k))
                .toList();
        assertEquals(List.of(), unexplained, () -> String.join("\n", differences.stream().limit(30).toList()));
    }

    /** What this reading gives for a corpus file, one line: ADR-068 M6's frozen baseline. */
    private static String output(String tool, String sources, Path file, Object value) {
        String key = file.toString();
        for (String dir : sources.split(File.pathSeparator)) {
            if (file.startsWith(dir)) key = Path.of(dir).getFileName() + "/" + Path.of(dir).relativize(file);
        }
        return tool + "\t" + key + "\t" + String.valueOf(value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Whether javac's references are JavaParser's plus some that JavaParser dropped because of a comment on or just
     * before the qualifier's line: JavaParser attaches the comment to the qualifier and prints it into its text, which is
     * then no class name. javac's reading keeps the reference.
     */
    static boolean onlyCommentedQualifiersAdded(String text, String expected, String actual) {
        var jp = REFERENCE.matcher(expected).results().map(m -> m.group()).toList();
        var jc = REFERENCE.matcher(actual).results().map(m -> m.group()).toList();
        if (!expected.substring(0, expected.indexOf("staticReferences")).equals(actual.substring(0, actual.indexOf("staticReferences")))) {
            return false;
        }
        var lines = text.split("\n", -1);
        int i = 0;
        for (String reference : jc) {
            if (i < jp.size() && jp.get(i).equals(reference)) {
                i++;
                continue;
            }
            var m = POSITION.matcher(reference);
            if (!m.find()) return false;
            int line = Integer.parseInt(m.group(1));
            int column = Integer.parseInt(m.group(2));
            // JavaParser attaches a comment on the qualifier's line, or just before it, to a node there.
            String before = text.substring(0, offset(lines, line, column)).stripTrailing();
            boolean commented = before.endsWith("*/") || lines[line - 1].contains("//") || lines[line - 1].contains("/*")
                    || line > 1 && lines[line - 2].contains("//");
            if (!commented) return false;
        }
        return i == jp.size();
    }

    private static final java.util.regex.Pattern REFERENCE = java.util.regex.Pattern.compile("ClassReference\\[[^\\]]*\\]");
    private static final java.util.regex.Pattern POSITION = java.util.regex.Pattern.compile("line=(\\d+), column=(\\d+)");

    private static int offset(String[] lines, int line, int column) {
        int offset = 0;
        for (int i = 0; i < line - 1; i++) offset += lines[i].length() + 1;
        return offset + column - 1;
    }

    private static void compare(Map<String, Integer> counts, List<String> differences, Path file, String tool,
                                String expected, String actual, String other) {
        if (expected.equals(actual)) {
            count(counts, tool + ":same");
        } else {
            count(counts, tool + other);
            differences.add(tool + other + "\t" + file + "\t" + clip(expected) + "\t" + clip(actual));
        }
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static String clip(String s) {
        s = s.replace('\t', ' ').replace('\n', ' ');
        return s;
    }
}
