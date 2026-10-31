package org.julclang.difftest;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LedgerTypeProvider;
import org.julclang.core.corpus.CorpusFiles;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 M1, E6: the javac gate's compile time against the JavaParser-only path, on a sample of the recorded corpus
 * (every 20th string-entry case the JavaParser frontend accepts). Warm: after a warm-up pass, each case is compiled
 * with the gate off and analyzed by javac without the gate's cache. Cold: the first compilation in a fresh JVM, gate
 * off against the gate in report mode (javac runs in full), for a few sources. Run by {@code :julc-difftest:adr068GateTiming -Padr068.corpus=<dir>}.
 */
@Tag("adr068-gate-timing")
class Adr068GateTimingTest {

    record Sample(String entry, String method, String main, List<String> libraries) {
    }

    static List<Sample> sample(Path corpus, int every) throws Exception {
        List<Path> dirs;
        try (Stream<Path> list = Files.list(corpus.resolve("cases"))) {
            dirs = list.sorted().toList();
        }
        var samples = new ArrayList<Sample>();
        for (int i = 0; i < dirs.size(); i += every) {
            String entry = null, method = null, main = null, lookup = null;
            var libraries = new ArrayList<String>();
            for (String line : Files.readAllLines(dirs.get(i).resolve("case.txt"), StandardCharsets.UTF_8)) {
                if (line.startsWith("entry: ")) entry = line.substring(7);
                if (line.startsWith("method: ")) method = line.substring(8);
                if (line.startsWith("lookup: ")) lookup = line.substring(8);
                if (line.startsWith("source: ")) main = text(corpus, line.substring(8));
                if (line.startsWith("library: ")) libraries.add(text(corpus, line.substring(9)));
            }
            if (main == null || !("default:" + StdlibRegistry.class.getName()).equals(lookup)) continue;
            if (!List.of("compile", "compileWithDetails", "compileContract", "compileMethod").contains(entry)) continue;
            var s = new Sample(entry, method, main, libraries);
            try {
                compile(s);
                samples.add(s);
            } catch (Throwable rejected) {
                // Only cases the JavaParser frontend accepts are timed.
            }
        }
        return samples;
    }

    static String text(Path corpus, String ref) throws Exception {
        return CorpusFiles.text(Files.readAllBytes(corpus.resolve("sources").resolve(ref.split(" ")[0] + ".java")));
    }

    static void compile(Sample s) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setJavacGate(JavacGateMode.OFF).setLogger(m -> { }));
        var result = s.entry().equals("compileMethod") ? compiler.compileMethod(s.main(), s.method(), s.libraries())
                : compiler.compile(s.main(), s.libraries());
        if (result.hasErrors()) throw new IllegalStateException("rejected");
    }

    static void javac(Sample s) {
        JavacFrontend.shared().analyze(List.of(JavacFrontend.Unit.of(s.main())),
                s.libraries().stream().map(JavacFrontend.Unit::of).toList());
    }

    @Test
    void warmAndColdCompileTime() throws Exception {
        Path corpus = Path.of(System.getProperty("adr068.corpus"));
        Path out = Path.of(System.getProperty("adr068.out", "build/adr068"));
        var samples = sample(corpus, 20);
        assertFalse(samples.isEmpty());
        for (var s : samples) { compile(s); javac(s); }   // warm-up
        long javaParser = 0, gate = 0;
        for (int round = 0; round < 3; round++) {
            for (var s : samples) {
                long t0 = System.nanoTime();
                compile(s);
                long t1 = System.nanoTime();
                javac(s);
                long t2 = System.nanoTime();
                javaParser += t1 - t0;
                gate += t2 - t1;
            }
        }
        var report = new StringBuilder();
        report.append(String.format(Locale.ROOT, "ADR-068 M1 E6 timing, %s, %d sampled cases x 3 rounds%n",
                Runtime.version(), samples.size()));
        report.append(String.format(Locale.ROOT, "warm: JavaParser-only compile %.1f ms/case; javac gate %.1f ms/case;"
                        + " gated compile / JavaParser-only = %.2f (javac alone / JavaParser = %.2f)%n",
                javaParser / 3e6 / samples.size(), gate / 3e6 / samples.size(),
                (double) (javaParser + gate) / javaParser, (double) gate / javaParser));

        Path dir = Files.createTempDirectory("adr068-cold");
        for (int i = 0; i < Math.min(3, samples.size()); i++) {
            var s = samples.get(i * samples.size() / 3);
            Path source = dir.resolve("main" + i + ".java");
            Files.writeString(source, s.main());
            var libs = new ArrayList<String>();
            for (int j = 0; j < s.libraries().size(); j++) {
                Path lib = dir.resolve("lib" + i + "-" + j + ".java");
                Files.writeString(lib, s.libraries().get(j));
                libs.add(lib.toString());
            }
            for (String mode : List.of("off", "report")) {
                var times = new ArrayList<String>();
                for (int run = 0; run < 3; run++) {
                    var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-cp", System.getProperty("java.class.path"), "-Djulc.javacGate=" + mode,
                            ColdProbe.class.getName(), s.entry(), String.valueOf(s.method()), source.toString()));
                    command.addAll(libs);
                    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                    assertEquals(0, process.waitFor(), output);
                    times.add(output.substring(output.lastIndexOf('\n') + 1));
                }
                report.append(String.format(Locale.ROOT, "cold (fresh JVM, first compile, gate %s), case %d: %s%n",
                        mode, i, String.join(" ", times)));
            }
        }
        // F402: the empty provider's metadata-only task, first use in a fresh JVM (LedgerTypeProvider).
        for (String mode : List.of("off", "report")) {
            var times = new ArrayList<String>();
            for (int run = 0; run < 3; run++) {
                var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), "-Djulc.javacGate=" + mode,
                        ColdProbe.class.getName(), "metadata");
                var process = new ProcessBuilder(command).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                assertEquals(0, process.waitFor(), output);
                times.add(output.substring(output.lastIndexOf('\n') + 1));
            }
            report.append(String.format(Locale.ROOT, "cold (fresh JVM) new LedgerTypeProvider(), gate %s: %s%n", mode,
                    String.join(" ", times)));
        }
        Files.createDirectories(out);
        Files.writeString(out.resolve("gate-timing.txt"), report.toString());
        System.out.print(report);
    }

    /** One compilation in a fresh JVM; prints its milliseconds. */
    public static final class ColdProbe {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("metadata")) {
                long t0 = System.nanoTime();
                new LedgerTypeProvider().types();
                System.out.printf(Locale.ROOT, "%.0f%n", (System.nanoTime() - t0) / 1e6);
                return;
            }
            var libraries = new ArrayList<String>();
            for (int i = 3; i < args.length; i++) libraries.add(Files.readString(Path.of(args[i])));
            var s = new Sample(args[0], args[1], Files.readString(Path.of(args[2])), libraries);
            long t0 = System.nanoTime();
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setLogger(m -> { }));
            var result = s.entry().equals("compileMethod") ? compiler.compileMethod(s.main(), s.method(), s.libraries())
                    : compiler.compile(s.main(), s.libraries());
            long t1 = System.nanoTime();
            if (result.hasErrors()) throw new IllegalStateException(result.diagnostics().toString());
            System.out.printf(Locale.ROOT, "%.0f%n", (t1 - t0) / 1e6);
        }
    }
}
