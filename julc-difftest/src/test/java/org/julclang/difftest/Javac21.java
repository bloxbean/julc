package org.julclang.difftest;

import org.junit.jupiter.api.Assumptions;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The compile-time oracle of ADR-067 (Pinned normative references, Verification 3): javac 21.0.3 invoked by
 * explicit path, never the JDK that runs the build. {@code javac --release 21} on a newer JDK is not equivalent,
 * because it runs the newer exhaustiveness algorithm; {@link #javacRelease21} gives that verdict only so that
 * disagreements can be reported.
 * <p>
 * The path comes from the system property {@code julc.javac21} (Gradle: {@code -Pjulc.javac21=<javac>}), then the
 * environment variable {@code JULC_JAVAC21}, then SDKMAN's {@code ~/.sdkman/candidates/java/21.0.3-tem/bin/javac}.
 * A javac whose {@code -version} is not exactly {@code javac 21.0.3} is refused. Without one, the tests that need it
 * are skipped, unless {@code julc.javac21.required} is {@code true}, which makes its absence a failure.
 */
final class Javac21 {

    static final String VERSION = "javac 21.0.3";

    /** A javac verdict: accepted, or rejected with its first error. */
    record Verdict(boolean accepted, String error) {
        static final Verdict OK = new Verdict(true, null);

        @Override
        public String toString() {
            return accepted ? "accepted" : "rejected: " + error;
        }
    }

    private static Path javac;
    private static boolean probed;

    private Javac21() {
    }

    /** The pinned javac, or skips (fails, if required) the calling test. */
    static synchronized Path require() {
        if (!probed) {
            probed = true;
            javac = locate();
        }
        boolean required = Boolean.getBoolean("julc.javac21.required");
        if (javac == null && required) {
            throw new AssertionError("julc.javac21.required is set, but no " + VERSION + " was found (set -Pjulc.javac21=<path to javac>)");
        }
        Assumptions.assumeTrue(javac != null, "no " + VERSION + " found; set -Pjulc.javac21=<path to javac> (ADR-067)");
        return javac;
    }

    private static Path locate() {
        var candidates = new ArrayList<String>();
        if (System.getProperty("julc.javac21") != null) candidates.add(System.getProperty("julc.javac21"));
        if (System.getenv("JULC_JAVAC21") != null) candidates.add(System.getenv("JULC_JAVAC21"));
        candidates.add(System.getProperty("user.home") + "/.sdkman/candidates/java/21.0.3-tem/bin/javac");
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isExecutable(path) && VERSION.equals(version(path))) return path;
        }
        return null;
    }

    private static String version(Path javacPath) {
        try {
            var process = new ProcessBuilder(javacPath.toString(), "-version").redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Compiles each case with the pinned javac 21.0.3, every case on its own, in one JVM of that JDK. A case is
     * its sources by file path ({@code Probe.java}, or a stub such as {@code org/julclang/ledger/Rational.java}).
     */
    static Map<String, Verdict> compile(Map<String, Map<String, String>> cases) {
        Path javacPath = require();
        Path java = javacPath.resolveSibling("java");
        try {
            Path root = Files.createTempDirectory("julc-javac21-");
            Path casesDir = Files.createDirectories(root.resolve("cases"));
            var names = new ArrayList<>(cases.keySet());
            for (int i = 0; i < names.size(); i++) {
                Path dir = Files.createDirectories(casesDir.resolve(String.format("c%05d", i)));
                for (var file : cases.get(names.get(i)).entrySet()) {
                    Path target = dir.resolve(file.getKey());
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, file.getValue());
                }
            }
            Path driver = root.resolve("Javac21Driver.java");
            try (InputStream in = Javac21.class.getResourceAsStream("/javac21/Javac21Driver.java")) {
                Files.copy(in, driver);
            }
            var process = new ProcessBuilder(java.toString(), driver.toString(), casesDir.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.MINUTES) || process.exitValue() != 0) {
                throw new AssertionError("the javac 21.0.3 driver failed:\n" + out);
            }
            var verdicts = new LinkedHashMap<String, Verdict>();
            for (String line : out.split("\n")) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t", 3);
                String name = names.get(Integer.parseInt(parts[0].substring(1)));
                verdicts.put(name, parts[1].equals("OK") ? Verdict.OK : new Verdict(false, parts[2]));
            }
            if (verdicts.size() != cases.size()) throw new AssertionError("the driver reported " + verdicts.size() + " of " + cases.size() + " cases");
            return verdicts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** The build JDK's javac with {@code --release 21}: reported beside the oracle, never a verdict. */
    static Verdict javacRelease21(Map<String, String> sources) {
        try {
            Path dir = Files.createTempDirectory("julc-javac-release21-");
            List<String> arguments = new ArrayList<>(List.of("--release", "21", "-proc:none", "-nowarn", "-d", dir.toString()));
            for (var file : sources.entrySet()) {
                Path target = dir.resolve("src").resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue());
                arguments.add(target.toString());
            }
            var errors = new ByteArrayOutputStream();
            int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors, arguments.toArray(String[]::new));
            if (status == 0) return Verdict.OK;
            String first = errors.toString(StandardCharsets.UTF_8).lines().filter(l -> l.contains("error:")).findFirst().orElse("error: (no message)");
            return new Verdict(false, first.substring(first.indexOf("error:") + 6).trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
