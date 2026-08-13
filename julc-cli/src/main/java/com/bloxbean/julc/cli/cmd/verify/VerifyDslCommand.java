package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.verification.dsl.SellerPaymentDsl;
import com.bloxbean.cardano.julc.verification.dsl.worker.DslWorkerRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

@Command(name = "dsl", description = "Run an experimental typed Java DSL property")
public final class VerifyDslCommand implements Callable<Integer> {
    @Parameters(index = "0", defaultValue = ".", description = "JuLC project directory")
    private Path projectDir;
    @Option(names = "--validator", required = true)
    private String validator;
    @Option(names = "--spec-class", required = true)
    private String specificationClass;
    @Option(names = "--spec-classpath", required = true)
    private String specificationClasspath;
    @Option(names = "--seller-field", required = true)
    private String sellerField;
    @Option(names = "--price-field", required = true)
    private String priceField;
    @Option(names = "--source", defaultValue = "VerificationSpecification.java")
    private String sourcePath;
    @Option(names = "--backend", defaultValue = "AUTO")
    private VerificationBackendKind backend;
    @Option(names = "--out-dir")
    private Path outputDirectory;
    @Option(names = "--fuel", defaultValue = "1500")
    private int fuel;
    @Option(names = "--recursive-depth", defaultValue = "4")
    private int recursiveDepth;
    @Option(names = "--worker-timeout", defaultValue = "30")
    private int workerTimeoutSeconds;
    @Option(names = "--force")
    private boolean force;

    @Override
    public Integer call() {
        try {
            if (workerTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("Worker timeout must be positive");
            }
            var loaded = DslContractLoader.load(projectDir, validator);
            Path worker = loaded.project().resolve("build/verification-dsl-worker")
                    .resolve(validator);
            String workerClasspath = specificationClasspath + File.pathSeparator
                    + System.getProperty("java.class.path");
            var candidate = new DslWorkerRunner().run(
                    workerClasspath, specificationClass, loaded.schema(), worker,
                    Duration.ofSeconds(workerTimeoutSeconds));
            var property = SellerPaymentDsl.resolve(candidate, loaded.schema(), validator,
                    sellerField, priceField, sourcePath);
            Path output = outputDirectory == null
                    ? loaded.project().resolve("verification").resolve(loaded.artifactId())
                    : outputDirectory.toAbsolutePath().normalize();
            VerificationProjectGenerator.generateSellerPayment(
                    loaded.blueprint(), property, fuel, recursiveDepth, output, force);
            var execution = new VerificationRunner().run(output, backend);
            System.out.println(execution.result().outcome() + ": "
                    + execution.result().reason());
            System.out.println("Property: " + property.template());
            System.out.println("Certificate: "
                    + output.resolve(VerificationRunner.RESULT_FILE));
            if (!execution.diagnostic().isBlank()) System.err.println(execution.diagnostic());
            return VerificationOutcome.parse(execution.result().outcome()).exitCode();
        } catch (Exception e) {
            System.err.println("DSL verification failed: " + e.getMessage());
            return 1;
        }
    }
}
