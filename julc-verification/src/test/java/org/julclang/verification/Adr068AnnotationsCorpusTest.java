package org.julclang.verification;

import org.julclang.compiler.JavaSourceParser;
import org.julclang.compiler.frontend.javac.JavacSyntax;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-068 M6's equivalence gate for the verification resolvers: on every corpus file, javac's parser and JavaParser
 * read the same {@link SourceAnnotations.Unit} (package, imports, every top-level type's annotations with their members,
 * values, positions and printed text), which is all the resolvers read of a source. Run by
 * {@code :julc-verification:adr068Tools -Padr068.tools.sources=...}.
 */
@Tag("adr068-tools")
class Adr068AnnotationsCorpusTest {

    /** The annotations the resolvers read; a printed text may differ only on others, where nothing reads it. */
    private static final Set<String> RESOLVED = Set.of("RequiresSigner", "ControlledMint", "Monotonic", "PreservesValue");

    private static final Map<String, String> ALLOWED = Map.of(
            "annotations:javac-rejects", "javac's parser rejects the source: both readings throw, in their own words",
            "annotations:reader-rejects", "JuLC's strict reader (JavaSourceParser, ADR-062 T24) rejects a source"
                    + " javac's parser reads; ADR-068 keeps javac's reading (D5)",
            "annotations:text-of-unresolved",
            "the printed text of an annotation no resolver reads, for a value JavaParser prints in its own layout");

    @Test
    void javacReadsTheAnnotationsJavaParserReads() throws IOException {
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
        for (Path file : files) {
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            } catch (CharacterCodingException e) {
                continue;
            }
            count(counts, "files");
            String expected = read(text, SourceAnnotations.Reader.JAVAPARSER);
            String actual = read(text, SourceAnnotations.Reader.JAVAC);
            String kind;
            if (expected.equals(actual)) {
                kind = "annotations:same";
            } else if (JavacSyntax.read(text).hasErrors()) {
                kind = "annotations:javac-rejects";
            } else if (expected.equals("ERROR") && !JavaSourceParser.parse(text).isSuccessful()) {
                kind = "annotations:reader-rejects";
            } else if (withoutUnresolvedText(text, SourceAnnotations.Reader.JAVAPARSER)
                    .equals(withoutUnresolvedText(text, SourceAnnotations.Reader.JAVAC))) {
                kind = "annotations:text-of-unresolved";
            } else {
                kind = "annotations:DIFFERENT";
            }
            count(counts, kind);
            if (!expected.equals(actual)) differences.add(kind + "\t" + file + "\t" + clip(expected) + "\t" + clip(actual));
            if (expected.contains("RequiresSigner") || expected.contains("ControlledMint")) count(counts, "files-with-verification-annotations");
        }
        var summary = new ArrayList<String>();
        counts.forEach((k, v) -> summary.add(k + "\t" + v));
        Files.write(out.resolve("annotations-summary.tsv"), summary);
        Files.write(out.resolve("annotations-differences.tsv"), differences);
        var unexplained = counts.keySet().stream()
                .filter(k -> k.startsWith("annotations:") && !k.equals("annotations:same") && !ALLOWED.containsKey(k))
                .toList();
        assertEquals(List.of(), unexplained, () -> String.join("\n", differences.stream().limit(30).toList()));
    }

    private static String read(String text, SourceAnnotations.Reader reader) {
        try {
            return SourceAnnotations.read(text, reader).toString();
        } catch (RuntimeException e) {
            return "ERROR";
        }
    }

    private static String withoutUnresolvedText(String text, SourceAnnotations.Reader reader) {
        try {
            var unit = SourceAnnotations.read(text, reader);
            var types = unit.types().stream().map(t -> new SourceAnnotations.Type(t.name(), t.annotations().stream()
                    .map(a -> RESOLVED.contains(a.identifier()) ? a : new SourceAnnotations.Annotation(a.name(), a.normal(),
                            a.members().stream().map(m -> new SourceAnnotations.Member(m.name(),
                                    new SourceAnnotations.Value(m.value().string(), m.value().integer(), ""))).toList(),
                            a.single() == null ? null : new SourceAnnotations.Value(a.single().string(), a.single().integer(), ""),
                            a.line(), a.column(), ""))
                    .toList())).toList();
            return new SourceAnnotations.Unit(unit.packageName(), unit.imports(), types).toString();
        } catch (RuntimeException e) {
            return "ERROR";
        }
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static String clip(String s) {
        s = s.replace('\t', ' ').replace('\n', ' ');
        return s.length() > 600 ? s.substring(0, 600) + "..." : s;
    }
}
