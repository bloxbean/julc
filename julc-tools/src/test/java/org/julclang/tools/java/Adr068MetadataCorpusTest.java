package org.julclang.tools.java;

import org.julclang.compiler.JavaSourceParser;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.SourceReader;
import org.julclang.compiler.validate.SubsetValidator;
import org.julclang.tools.model.CheckResponse;
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
 * ADR-068 M6's equivalence gate for {@link JavaMetadataExtractor}: on every corpus file, the whole response of each
 * reading, diagnostics and validity included. The subset's diagnostics are each frontend's: the JavaParser frontend's
 * validator, or the javac frontend's subset check ({@code JavacCompilation.subset}), as a compilation reports them. A
 * response that differs only there is classified by how the two frontends' diagnostics differ, and written down with
 * both. Run by {@code :julc-tools:adr068Tools -Padr068.tools.sources=...}.
 */
@Tag("adr068-tools")
class Adr068MetadataCorpusTest {

    private static final JulcCompiler COMPILER = new JulcCompiler();

    private static final Map<String, String> ALLOWED = Map.ofEntries(
            Map.entry("metadata:both-unparsable", "both parsers reject the source; each reports in its own words (D5)"),
            Map.entry("metadata:javac-rejects", "javac's parser rejects the source, which JavaParser's reading accepted"),
            Map.entry("metadata:reader-rejects", "JuLC's strict reader (ADR-062 T24) rejects a source javac's parser reads;"
                    + " ADR-068 keeps javac's reading (D5)"),
            Map.entry("subset:same-codes", "the same codes at the same positions, in the same order; a message's words differ"),
            Map.entry("subset:same-first-error", "the first error's code and position agree (the one a compilation stops"
                    + " on); the frontends collect the rest differently"),
            Map.entry("subset:javac-attribution", "javac's attribution rejects the source (JULC0085, D5), which the"
                    + " JavaParser validator does not read"),
            Map.entry("subset:not-ported", "the javac frontend stops at a construct it does not lower yet (JULC0084): M5's"),
            Map.entry("subset:javac-codes", "the javac frontend rejects with its own code where the JavaParser frontend's"
                    + " validator had another or none (I2a's coded rejections, D13's policy rows)"));

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
        var outputs = new ArrayList<String>();
        for (Path file : files) {
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            } catch (CharacterCodingException e) {
                continue;
            }
            count(counts, "files");
            var expected = JavaMetadataExtractor.extract(text, SourceReader.JAVAPARSER, COMPILER, List.of());
            var actual = JavaMetadataExtractor.extract(text, SourceReader.JAVAC, COMPILER, List.of());
            outputs.add(output("metadata", sources, file, actual));
            if (expected.contractName() != null) count(counts, "validators");
            var javaParserSubset = javaParserSubset(text);
            if (!javaParserSubset.isEmpty()) count(counts, "javaparser-subset-diagnostics-files");
            boolean javacRejects = JavacSyntax.read(text).hasErrors();
            boolean readerRejects = !javacRejects && !JavaSourceParser.parse(text).isSuccessful();
            String kind;
            if (expected.equals(actual)) {
                kind = "metadata:same";
            } else if (javacRejects && unparsable(expected)) {
                kind = "metadata:both-unparsable";
            } else if (javacRejects) {
                kind = "metadata:javac-rejects";
            } else if (readerRejects) {
                kind = "metadata:reader-rejects";
            } else {
                var javacSubset = JavacMetadataReading.subset(text, COMPILER, List.of());
                if (structure(expected, javaParserSubset.size()).equals(structure(actual, javacSubset.size()))) {
                    kind = "subset:" + subsetClass(javaParserSubset, javacSubset);
                    count(counts, expected.valid() == actual.valid() ? "subset-validity:same" : "subset-validity:differs");
                } else {
                    kind = "metadata:DIFFERENT";
                }
            }
            count(counts, kind);
            // The files where the JavaParser frontend's validator reports something: what javac's check makes of them.
            if (!javaParserSubset.isEmpty()) count(counts, "javaparser-subset-files " + kind);
            if (!kind.equals("metadata:same")) differences.add(kind + "\t" + file + "\t" + expected + "\t" + actual);
        }
        var summary = new ArrayList<String>();
        counts.forEach((k, v) -> summary.add(k + "\t" + v));
        Files.write(out.resolve("metadata-summary.tsv"), summary);
        Files.write(out.resolve("metadata-differences.tsv"), differences);
        Files.write(out.resolve("metadata-outputs.tsv"), outputs);
        var unexplained = counts.keySet().stream()
                .filter(k -> k.startsWith("metadata:") || k.startsWith("subset:"))
                .filter(k -> !k.equals("metadata:same") && !ALLOWED.containsKey(k))
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

    /** The JavaParser frontend's validator's diagnostics, which its reading puts first. */
    private static List<CompilerDiagnostic> javaParserSubset(String text) {
        var parsed = JavaSourceParser.parse(text);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return List.of();
        return new SubsetValidator().validate(parsed.getResult().get());
    }

    /** How the two frontends' subset diagnostics differ. */
    private static String subsetClass(List<CompilerDiagnostic> javaParser, List<CompilerDiagnostic> javac) {
        if (javac.stream().anyMatch(d -> "JULC0085".equals(d.code()))) return "javac-attribution";
        if (javac.stream().anyMatch(d -> "JULC0084".equals(d.code()))) return "not-ported";
        if (javaParser.stream().map(Adr068MetadataCorpusTest::site).toList()
                .equals(javac.stream().map(Adr068MetadataCorpusTest::site).toList())) {
            return "same-codes";
        }
        var first = javaParser.stream().filter(CompilerDiagnostic::isError).findFirst().map(Adr068MetadataCorpusTest::site);
        var firstOnJavac = javac.stream().filter(CompilerDiagnostic::isError).findFirst().map(Adr068MetadataCorpusTest::site);
        if (first.isPresent() && first.equals(firstOnJavac)) return "same-first-error";
        return "javac-codes";
    }

    private static String site(CompilerDiagnostic d) {
        return d.level() + " " + d.code() + " " + d.line() + ":" + d.column();
    }

    /** A parse failure: no contract and only errors without a code. */
    private static boolean unparsable(CheckResponse response) {
        return response.contractName() == null && !response.valid()
                && response.diagnostics().stream().allMatch(d -> d.code() == null && "ERROR".equals(d.level()))
                && response.diagnostics().stream().noneMatch(d -> d.message().startsWith("No validator annotation"));
    }

    /** The response but for its first {@code subset} diagnostics and its validity: what the reading itself found. */
    private static String structure(CheckResponse r, int subset) {
        var own = r.diagnostics().subList(Math.min(subset, r.diagnostics().size()), r.diagnostics().size());
        return String.join(" | ", String.valueOf(r.contractName()), String.valueOf(r.purpose()),
                String.valueOf(r.params()), String.valueOf(r.datumName()), String.valueOf(r.datumFields()),
                String.valueOf(r.redeemerVariants()), String.valueOf(r.redeemerFields()), String.valueOf(own));
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }
}
