package org.julclang.compiler.corpus;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-068 M0's corpus recorder: off by default, and recording changes no compilation.
 */
class CorpusRecorderTest {

    @Test
    void inactiveWithoutTheProperty() {
        assumeTrue(System.getProperty(CorpusRecorder.PROPERTY) == null, "this JVM is recording a corpus");
        assertNull(CorpusRecorder.ACTIVE);
        new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(CorpusRecorderProbe.VALIDATOR);
        assertNull(CorpusRecorder.ACTIVE);
    }

    /**
     * The probe prints the same bytes and rejections with and without recording, and two recordings of it write
     * the same tree: one case per entry point and options, every level marked, the probe as the origin.
     */
    @Test
    void recordingChangesNoCompilationAndIsDeterministic(@TempDir Path temp) throws Exception {
        String plain = probe(null);
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");
        assertEquals(plain, probe(first));
        assertEquals(plain, probe(second));
        assertFalse(plain.contains("failed "), plain);
        assertTrue(plain.contains("rejected "), plain);

        var tree = tree(first);
        assertEquals(tree, tree(second));
        var caseFiles = tree.keySet().stream().filter(p -> p.endsWith("/case.txt")).toList();
        var entries = new ArrayList<String>();
        for (String caseFile : caseFiles) entries.add(tree.get(caseFile).lines()
                .filter(l -> l.startsWith("entry: ")).findFirst().orElseThrow());
        assertEquals(List.of("compile", "compileContract", "compileMethod", "compilePath", "JavaLibraryProvider"),
                entries.stream().map(e -> e.substring(7)).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        for (String caseFile : caseFiles) {
            String dir = caseFile.substring(0, caseFile.length() - "case.txt".length());
            for (String level : List.of("NONE", "BASELINE", "PV11_SAFE", "PV11_COSTED")) {
                assertTrue(tree.containsKey(dir + "level-" + level), dir + level);
            }
            assertTrue(tree.entrySet().stream().anyMatch(e -> e.getKey().startsWith(dir + "origin-")
                    && e.getValue().startsWith(CorpusRecorderProbe.class.getName() + "#")), dir);
        }
        assertTrue(tree.get(caseFiles.stream().filter(f -> tree.get(f).contains("entry: compilePath"))
                .findFirst().orElseThrow()).contains(" Sample.java"));
        String validator = "sources/" + CorpusRecorder.sha256(CorpusRecorderProbe.VALIDATOR) + ".java";
        assertEquals(CorpusRecorderProbe.VALIDATOR, tree.get(validator));
    }

    /** A source UTF-8 cannot encode (an unpaired surrogate) is recorded without loss, and without failing. */
    @Test
    void sourcesAreStoredWithoutLoss(@TempDir Path temp) throws IOException {
        String malformed = "class S { String s = \"\uD800\"; }";
        assertEquals(malformed, CorpusRecorder.text(CorpusRecorder.bytes(malformed)));
        assertEquals("plain é", CorpusRecorder.text(CorpusRecorder.bytes("plain é")));
        new CorpusRecorder(temp).record("compileMethod", "m", new CorpusRecorder.Source(malformed, null), List.of(),
                null, new CompilerOptions());
        Path stored = temp.resolve("sources").resolve(CorpusRecorder.sha256(malformed) + ".java");
        assertEquals(malformed, CorpusRecorder.text(Files.readAllBytes(stored)));
    }

    /** Runs the probe in a child JVM, recording into {@code corpus} when it is not null. */
    private static String probe(Path corpus) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElse("java");
        var command = new ArrayList<String>();
        command.add(java);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        if (corpus != null) command.add("-D" + CorpusRecorder.PROPERTY + "=" + corpus);
        command.add(CorpusRecorderProbe.class.getName());
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(5, TimeUnit.MINUTES));
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    /** Every regular file under {@code root} by relative path, with its content. */
    private static TreeMap<String, String> tree(Path root) throws IOException {
        var files = new TreeMap<String, String>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        return files;
    }
}
