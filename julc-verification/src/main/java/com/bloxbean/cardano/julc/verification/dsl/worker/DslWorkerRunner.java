package com.bloxbean.cardano.julc.verification.dsl.worker;

import com.bloxbean.cardano.julc.compiler.schema.ContractSchema;
import com.bloxbean.cardano.julc.verification.dsl.DslPropertyValidator;
import com.bloxbean.cardano.julc.verification.dsl.PropertyIrCodec;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Runs a trusted-source property builder out of process with strict output bounds. */
public final class DslWorkerRunner {
    public static final long MAX_OUTPUT_BYTES = 1_048_576;
    public static final int MAX_AST_NODES = DslPropertyValidator.MAX_AST_NODES;

    public DslPropertySet run(
            String classPath,
            String specificationClass,
            ContractSchema schema,
            Path workingDirectory,
            Duration timeout) throws IOException, InterruptedException {
        Files.createDirectories(workingDirectory);
        Path output = workingDirectory.resolve("candidate-property-ir.json");
        Files.deleteIfExists(output);
        String java = resolveJavaExecutable(
                System.getProperty("java.home"), System.getenv("JAVA_HOME"),
                System.getenv("PATH")).toString();
        var command = List.of(java, "-Xmx128m",
                "-Duser.home=" + workingDirectory,
                "-Djava.io.tmpdir=" + workingDirectory,
                "-cp", classPath,
                DslWorkerMain.class.getName(), specificationClass, output.toString());
        var builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(workingDirectory.resolve("worker.log").toFile());
        builder.environment().clear();
        builder.environment().put("LANG", "C");
        builder.environment().put("LC_ALL", "C");
        var process = builder.start();
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor();
            throw new IOException("Property worker timed out after " + timeout);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Property worker exited " + process.exitValue()
                    + "; see " + workingDirectory.resolve("worker.log"));
        }
        DslPropertySet propertySet = PropertyIrCodec.read(output, MAX_OUTPUT_BYTES);
        DslPropertyValidator.validate(propertySet, schema, MAX_AST_NODES);
        Path canonical = workingDirectory.resolve("verification-property-dsl.json");
        PropertyIrCodec.write(canonical, propertySet);
        return propertySet;
    }

    static Path resolveJavaExecutable(
            String javaHomeProperty, String javaHomeEnvironment, String pathEnvironment)
            throws IOException {
        String executable = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        for (String home : List.of(
                javaHomeProperty == null ? "" : javaHomeProperty,
                javaHomeEnvironment == null ? "" : javaHomeEnvironment)) {
            if (home.isBlank()) continue;
            Path candidate = Path.of(home, "bin", executable).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        if (pathEnvironment != null && !pathEnvironment.isBlank()) {
            for (String entry : pathEnvironment.split(Pattern.quote(java.io.File.pathSeparator))) {
                if (entry.isBlank()) continue;
                Path candidate = Path.of(entry, executable).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IOException("Java runtime for the DSL property worker was not found; "
                + "install a JDK and configure JAVA_HOME or PATH");
    }
}
