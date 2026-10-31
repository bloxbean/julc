package org.julclang.compiler.corpus;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.corpus.CorpusFiles;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.corpus.EvaluationRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-068 M0's corpus and evaluation recorders: off by default, and recording changes no compilation or evaluation.
 */
class CorpusRecorderTest {

    @Test
    void inactiveWithoutTheProperty() {
        assumeTrue(System.getProperty(CorpusRecorder.PROPERTY) == null, "this JVM is recording a corpus");
        assertNull(CorpusRecorder.ACTIVE);
        assertNull(EvaluationRecorder.ACTIVE);
        new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(CorpusRecorderProbe.VALIDATOR);
        assertNull(CorpusRecorder.ACTIVE);
    }

    /**
     * The probe prints the same bytes, PIR, outcomes and rejections with and without recording, and two recordings of
     * it write the same tree: one case per entry point and options, every level marked, the probe as the origin, the
     * specialization request, the custom lookup kept apart from the default, and the evaluation.
     */
    @Test
    void recordingChangesNothingAndIsDeterministic(@TempDir Path temp) throws Exception {
        String plain = probe(null);
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");
        assertEquals(plain, probe(first));
        assertEquals(plain, probe(second));
        assertFalse(plain.contains("failed "), plain);
        assertTrue(plain.contains("rejected "), plain);

        var tree = tree(first);
        assertEquals(tree.keySet(), tree(second).keySet());
        assertEquals(tree.entrySet().stream().filter(e -> !e.getKey().contains("/origin-")).toList(),
                tree(second).entrySet().stream().filter(e -> !e.getKey().contains("/origin-")).toList());
        var caseFiles = tree.keySet().stream().filter(p -> p.startsWith("cases/") && p.endsWith("/case.txt")).toList();
        var entries = new TreeSet<String>();
        var lookups = new TreeSet<String>();
        for (String caseFile : caseFiles) {
            for (String line : tree.get(caseFile).lines().toList()) {
                if (line.startsWith("entry: ")) entries.add(line.substring(7));
                if (line.startsWith("lookup: ")) lookups.add(line.substring(8).replaceAll(":[0-9a-f]{16}$", ":<fp>"));
            }
        }
        assertEquals(new TreeSet<>(CorpusRecorderProbe.ENTRIES), entries);
        assertEquals(new TreeSet<>(List.of("default:org.julclang.stdlib.StdlibRegistry",
                "custom:org.julclang.stdlib.StdlibRegistry:<fp>")), lookups);
        for (String caseFile : caseFiles) {
            String dir = caseFile.substring(0, caseFile.length() - "case.txt".length());
            for (String level : List.of("NONE", "BASELINE", "PV11_SAFE", "PV11_COSTED")) {
                assertTrue(tree.containsKey(dir + "level-" + level), dir + level);
            }
            assertTrue(tree.entrySet().stream().anyMatch(e -> e.getKey().startsWith(dir + "origin-")
                    && e.getValue().startsWith(CorpusRecorderProbe.class.getName() + "#")), dir);
        }
        var siblingCase = caseFiles.stream().map(tree::get)
                .filter(t -> t.contains("entry: compilePath") && t.contains(" Twice.java")).findFirst().orElseThrow();
        assertTrue(siblingCase.contains(" Sample.java"), siblingCase);
        var instantiations = tree.entrySet().stream().filter(e -> e.getKey().contains("/instantiate-")).toList();
        assertEquals(1, instantiations.size(), instantiations.toString());
        assertTrue(instantiations.getFirst().getValue().startsWith("(instantiate \"sample.Twice.count\""));
        assertTrue(tree.keySet().stream().anyMatch(p -> p.startsWith("evaluations/") && p.endsWith("/evaluation.txt")));
        String validator = "sources/" + CorpusFiles.sha256(CorpusRecorderProbe.VALIDATOR) + ".java";
        assertEquals(CorpusRecorderProbe.VALIDATOR, tree.get(validator));
    }

    /** A source UTF-8 cannot encode (an unpaired surrogate) is recorded without loss, and without failing. */
    @Test
    void sourcesAreStoredWithoutLoss(@TempDir Path temp) throws IOException {
        String malformed = "class S { String s = \"\uD800\"; }";
        assertEquals(malformed, CorpusFiles.text(CorpusFiles.bytes(malformed)));
        assertEquals("plain é", CorpusFiles.text(CorpusFiles.bytes("plain é")));
        new FileCorpusRecorder(temp.toString()).record("compileMethod", "m", new CorpusRecorder.Source(malformed, null),
                List.of(), null, new CompilerOptions());
        Path stored = temp.resolve("sources").resolve(CorpusFiles.sha256(malformed) + ".java");
        assertEquals(malformed, CorpusFiles.text(Files.readAllBytes(stored)));
    }

    /** A custom registry, even one equal to the default but for one registration, is never the default (F201). */
    @Test
    void lookupIdentity() {
        var recorder = new FileCorpusRecorder("unused");
        assertEquals("none", recorder.identity(null));
        assertEquals("default:org.julclang.stdlib.StdlibRegistry", recorder.identity(StdlibRegistry.defaultRegistry()));
        var extended = StdlibRegistry.defaultRegistry();
        extended.register("example.registry.RegistryOnly", "accept", args -> args.getFirst());
        assertTrue(recorder.identity(extended).startsWith("custom:org.julclang.stdlib.StdlibRegistry:"));
        assertTrue(recorder.identity(new StdlibRegistry()).startsWith("custom:org.julclang.stdlib.StdlibRegistry:"));
    }

    /** A specialization request survives its text form, producer types included. */
    @Test
    void requestTextRoundTrips() {
        var record = new PirType.RecordType("demo.Quote", List.of(new PirType.Field("amount", new PirType.IntegerType()),
                new PirType.Field("tag \"q\"", new PirType.ListType(new PirType.DataType()))));
        var sum = new PirType.SumType("demo.Choice", List.of(new PirType.Constructor("A", 0, List.of()),
                new PirType.Constructor("B", 1, List.of(new PirType.Field("x", new PirType.NamedTypeRef("id", "demo.Choice",
                        PirType.NamedKind.SUM))))));
        var request = new LibraryRequest.Instantiate("demo.Seq.member", List.of(
                new LibraryType.Reference("List", List.of(new LibraryType.Reference("Bytes", List.of()))),
                LibraryType.Reference.variable("t")), Map.of("demo.Quote", record, "demo.Choice", sum,
                "demo.Map", new PirType.MapType(new PirType.ByteStringType(), new PirType.OptionalType(new PirType.BoolType()))));
        assertEquals(request, RequestText.decode(RequestText.encode(request)));
    }

    /** Runs the probe in a child JVM, recording into {@code corpus} when it is not null. */
    private static String probe(Path corpus) throws IOException, InterruptedException {
        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        var command = new ArrayList<String>();
        command.add(javaCommand);
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
                String name = root.relativize(p).toString().replace('\\', '/');
                files.put(name, name.endsWith(".cbor") ? "cbor" : Files.readString(p));
            }
        }
        return files;
    }
}
