package org.julclang.tools.java;

import org.julclang.compiler.frontend.javac.JavacSyntax;
import org.julclang.tools.model.CheckResponse;
import org.julclang.tools.model.DiagnosticDto;
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
 * ADR-068 M6's equivalence gate for {@link JavaMetadataExtractor}: on every corpus file, javac's reading gives the
 * JavaParser reading's metadata (contract, purpose, params, datum, redeemer) and its own diagnostics. The JavaParser
 * reading also reports the JavaParser frontend's subset diagnostics (those with a code), which javac's reading does not
 * make; they are compared out and counted. Run by {@code :julc-tools:adr068Tools -Padr068.tools.sources=...}.
 */
@Tag("adr068-tools")
class Adr068MetadataCorpusTest {

    private static final Map<String, String> ALLOWED = Map.of(
            "metadata:both-unparsable", "both parsers reject the source; each reports in its own words (D5)",
            "metadata:javac-rejects", "javac's parser rejects the source, which JavaParser's reading accepted");

    @Test
    void javacReadsTheMetadataJavaParserReads() throws IOException {
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
            var expected = JavaMetadataExtractor.extract(text, JavaMetadataExtractor.Reader.JAVAPARSER);
            var actual = JavaMetadataExtractor.extract(text, JavaMetadataExtractor.Reader.JAVAC);
            if (expected.contractName() != null) count(counts, "validators");
            long subset = expected.diagnostics().stream().filter(d -> d.code() != null).count();
            if (subset > 0) count(counts, "javaparser-subset-diagnostics-files");
            String kind;
            boolean javacRejects = JavacSyntax.read(text).hasErrors();
            if (comparable(expected).equals(comparable(actual))) {
                kind = "metadata:same";
            } else if (javacRejects && unparsable(expected)) {
                kind = "metadata:both-unparsable";
            } else if (javacRejects) {
                kind = "metadata:javac-rejects";
            } else {
                kind = "metadata:DIFFERENT";
            }
            count(counts, kind);
            if (!kind.equals("metadata:same")) {
                differences.add(kind + "\t" + file + "\t" + clip(comparable(expected)) + "\t" + clip(comparable(actual)));
            }
        }
        var summary = new ArrayList<String>();
        counts.forEach((k, v) -> summary.add(k + "\t" + v));
        Files.write(out.resolve("metadata-summary.tsv"), summary);
        Files.write(out.resolve("metadata-differences.tsv"), differences);
        var unexplained = counts.keySet().stream()
                .filter(k -> k.startsWith("metadata:") && !k.equals("metadata:same") && !ALLOWED.containsKey(k))
                .toList();
        assertEquals(List.of(), unexplained, () -> String.join("\n", differences.stream().limit(30).toList()));
    }

    /** A parse failure: no contract and only errors without a code. */
    private static boolean unparsable(CheckResponse response) {
        return response.contractName() == null && !response.valid()
                && response.diagnostics().stream().allMatch(d -> d.code() == null && "ERROR".equals(d.level()))
                && response.diagnostics().stream().noneMatch(d -> d.message().startsWith("No validator annotation"));
    }

    /** The response without the subset diagnostics (coded), and without validity when they decided it. */
    private static String comparable(CheckResponse r) {
        List<DiagnosticDto> own = r.diagnostics().stream().filter(d -> d.code() == null).toList();
        boolean ownErrors = own.stream().anyMatch(d -> "ERROR".equals(d.level()));
        return String.join(" | ", String.valueOf(!ownErrors), String.valueOf(r.contractName()), String.valueOf(r.purpose()),
                String.valueOf(r.params()), String.valueOf(r.datumName()), String.valueOf(r.datumFields()),
                String.valueOf(r.redeemerVariants()), String.valueOf(r.redeemerFields()), String.valueOf(own));
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static String clip(String s) {
        s = s.replace('\t', ' ').replace('\n', ' ');
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
