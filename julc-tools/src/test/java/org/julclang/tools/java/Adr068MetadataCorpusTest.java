package org.julclang.tools.java;

import org.julclang.compiler.JulcCompiler;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-068 M6's equivalence gate for JavaMetadataExtractor: on every {@code .java} file of the corpus, what it gives equals what it gave at
 * M6's start, when javac's reading had been compared with JavaParser's ({@code adr/evidence/068-m6a-tools.md}, and the
 * baseline's digests in {@code adr/evidence/068/m6-tools-baseline.tsv}). Run by
 * {@code :julc-tools:adr068Tools -Padr068.tools.sources=<dir>[:<dir>...] -Padr068.tools.baseline=<dir> [-Padr068.tools.out=<dir>]}.
 */
@Tag("adr068-tools")
class Adr068MetadataCorpusTest {

    private static final JulcCompiler COMPILER = new JulcCompiler();

    @Test
    void theMetadataIsTheBaselines() throws IOException {
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
        var outputs = new ArrayList<String>();
        for (Path file : files) {
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            } catch (CharacterCodingException e) {
                continue;
            }
            outputs.add(stable(output("metadata", sources, file, JavaMetadataExtractor.extract(text, COMPILER, List.of()))));
        }
        Files.write(out.resolve("metadata-outputs.tsv"), outputs);
        assertBaseline("metadata-outputs.tsv", outputs);
    }

    /**
     * What may differ from the baseline. The one word of javac's that a run does not fix: where a source reaches a class the
     * closed platform does not hold (a test of this repository), which class javac names first varies between runs, and such a
     * source is rejected either way. And two fix strings M6 rewrote, JULC0087's way back to the JavaParser frontend and
     * JULC0101's, so a baseline's line reads as today's.
     */
    private static String stable(String line) {
        return line.replaceAll("cannot access [\\w.]+\\\\n  class file for [\\w.$]+ not found", "cannot access a class of no platform")
                .replace(", which keeps the script the JavaParser frontend made for a.equals(b)", "")
                .replace(" Until ADR-068 M6 the JavaParser frontend, which compares the bytes on-chain but by identity on the JVM,"
                        + " stays selectable: julc build --frontend javaparser, julc { frontend = 'javaparser' } in Gradle, or"
                        + " CompilerOptions.setFrontend(CompilerFrontend.JAVAPARSER).", "")
                .replace("suggestion=Give the method another name.",
                        "suggestion=Give the method or component another name, and read the component through its generated accessor.");
    }

    /** What the tool gives for a corpus file, one line. */
    private static String output(String tool, String sources, Path file, Object value) {
        String key = file.toString();
        for (String dir : sources.split(File.pathSeparator)) {
            if (file.startsWith(dir)) key = Path.of(dir).getFileName() + "/" + Path.of(dir).relativize(file);
        }
        return tool + "\t" + key + "\t" + String.valueOf(value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** ADR-068 M6's gate: this run's lines against the frozen baseline {@code adr068.tools.baseline}, if one is named; they must be equal. */
    private static void assertBaseline(String file, List<String> lines) throws IOException {
        String baseline = System.getProperty("adr068.tools.baseline");
        if (baseline == null) return;
        var frozen = Files.readAllLines(Path.of(baseline).resolve(file)).stream().map(Adr068MetadataCorpusTest::stable).toList();
        var moved = new ArrayList<String>();
        for (int i = 0; i < Math.max(frozen.size(), lines.size()) && moved.size() < 20; i++) {
            String was = i < frozen.size() ? frozen.get(i) : "(none)";
            String is = i < lines.size() ? lines.get(i) : "(none)";
            if (!was.equals(is)) moved.add(was + "  ->  " + is);
        }
        assertEquals(List.of(), moved, file + " moved");
        assertEquals(frozen.size(), lines.size());
    }
}
