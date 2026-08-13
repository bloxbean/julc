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

/** Runs a trusted-source property builder out of process with strict output bounds. */
public final class DslWorkerRunner {
    public static final long MAX_OUTPUT_BYTES = 1_048_576;
    public static final int MAX_AST_NODES = 10_000;

    public DslPropertySet run(
            String classPath,
            String specificationClass,
            ContractSchema schema,
            Path workingDirectory,
            Duration timeout) throws IOException, InterruptedException {
        Files.createDirectories(workingDirectory);
        Path output = workingDirectory.resolve("candidate-property-ir.json");
        Files.deleteIfExists(output);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
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
}
