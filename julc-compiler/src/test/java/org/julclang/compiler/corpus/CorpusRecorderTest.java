package org.julclang.compiler.corpus;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.core.corpus.CorpusFiles;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
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

    private static final String BUILTINS = "org.julclang.stdlib.Builtins";

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
                List.of(), null, CorpusRecorder.Options.of(new CompilerOptions()));
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
        // Re-registering a key, even with its own builder, clears the default's token.
        var reregistered = StdlibRegistry.defaultRegistry();
        reregistered.register(BUILTINS, "equalsByteString", reregistered.lookupBuilder(BUILTINS, "equalsByteString")
                .orElseThrow(), reregistered.requirements(BUILTINS, "equalsByteString"));
        assertTrue(recorder.identity(reregistered).startsWith("custom:org.julclang.stdlib.StdlibRegistry:"));
    }

    /**
     * F310's probe: a default registry whose {@code equalsByteString} is given {@code lessThanByteString}'s builder,
     * with its own requirements. The table-built builders share a class, so the registry has the default's keys,
     * builder classes and requirements, yet it compiles the same source to a different program; it is custom.
     */
    @Test
    void aSwappedBuilderIsNotTheDefault() {
        var swapped = StdlibRegistry.defaultRegistry();
        var defaults = StdlibRegistry.defaultRegistry();
        var lessThan = swapped.lookupBuilder(BUILTINS, "lessThanByteString").orElseThrow();
        assertEquals(lessThan.getClass(), defaults.lookupBuilder(BUILTINS, "equalsByteString").orElseThrow().getClass(),
                "the probe needs builders of one class");
        swapped.register(BUILTINS, "equalsByteString", lessThan, swapped.requirements(BUILTINS, "equalsByteString"));
        var recorder = new FileCorpusRecorder("unused");
        assertEquals("default:org.julclang.stdlib.StdlibRegistry", recorder.identity(defaults));
        assertTrue(recorder.identity(swapped).startsWith("custom:org.julclang.stdlib.StdlibRegistry:"),
                recorder.identity(swapped));
        String source = """
                import org.julclang.stdlib.Builtins;
                class Swap {
                    static boolean m() {
                        byte[] a = new byte[]{1};
                        byte[] b = new byte[]{2};
                        return Builtins.equalsByteString(a, b);
                    }
                }
                """;
        var custom = CompilerTestVm.pv11().evaluate(new JulcCompiler(swapped).compileMethod(source, "m").program());
        var standard = CompilerTestVm.pv11().evaluate(new JulcCompiler(defaults).compileMethod(source, "m").program());
        assertEquals(new Term.Const(Constant.bool(true)), result(custom));
        assertEquals(new Term.Const(Constant.bool(false)), result(standard));
    }

    private static Term result(EvalResult result) {
        assertTrue(result instanceof EvalResult.Success, result.toString());
        return ((EvalResult.Success) result).resultTerm();
    }

    /**
     * F313's probe: a provider is built with PV11 options, the caller then changes those options to PV10, and the
     * provider is asked for a specialization. The specialization belongs to the provider's case, which was compiled
     * at PV11; no PV10 case is recorded.
     */
    @Test
    void specializationsBelongToTheProvidersCase(@TempDir Path temp) throws Exception {
        Path corpus = temp.resolve("corpus");
        String output = run(CorpusRecorderOptionsProbe.class, corpus);
        assertTrue(output.startsWith("materialized "), output);
        var tree = tree(corpus);
        var cases = tree.entrySet().stream().filter(e -> e.getKey().endsWith("/case.txt")).toList();
        assertEquals(1, cases.size(), cases.toString());
        assertTrue(cases.getFirst().getValue().contains("target: plutus-v3-pv11-uplc-1.1.0\n"), cases.toString());
        assertFalse(tree.values().stream().anyMatch(v -> v.contains("pv10")), tree.keySet().toString());
        String dir = cases.getFirst().getKey().substring(0, cases.getFirst().getKey().length() - "case.txt".length());
        var instantiations = tree.keySet().stream().filter(k -> k.contains("/instantiate-")).toList();
        assertEquals(1, instantiations.size(), instantiations.toString());
        assertTrue(instantiations.getFirst().startsWith(dir), instantiations + " under " + dir);
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
        return run(CorpusRecorderProbe.class, corpus);
    }

    /** Runs {@code main} in a child JVM, recording into {@code corpus} when it is not null. */
    private static String run(Class<?> main, Path corpus) throws IOException, InterruptedException {
        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        var command = new ArrayList<String>();
        command.add(javaCommand);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        if (corpus != null) command.add("-D" + CorpusRecorder.PROPERTY + "=" + corpus);
        command.add(main.getName());
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
