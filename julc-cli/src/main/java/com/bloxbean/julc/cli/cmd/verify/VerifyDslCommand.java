package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.verification.SellerPaymentProperty;
import com.bloxbean.cardano.julc.verification.ControlledMintProperty;
import com.bloxbean.cardano.julc.verification.OneShotMintProperty;
import com.bloxbean.cardano.julc.verification.ComposedDslProperty;
import com.bloxbean.cardano.julc.verification.VerificationProperty;
import com.bloxbean.cardano.julc.verification.dsl.MintingDsl;
import com.bloxbean.cardano.julc.verification.dsl.ComposedDslPromotion;
import com.bloxbean.cardano.julc.verification.dsl.SellerPaymentDsl;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.worker.DslWorkerRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

@Command(name = "dsl", description = "Run an experimental typed Java DSL property")
public final class VerifyDslCommand implements Callable<Integer> {
    @Parameters(index = "0", defaultValue = ".", description = "JuLC project directory")
    private Path projectDir;
    @Option(names = "--validator", required = true)
    private String validator;
    @Option(names = "--purpose",
            description = "Required for a multi-validator: ${COMPLETION-CANDIDATES}")
    private VerificationPurpose purpose;
    @Option(names = "--spec-class", required = true)
    private String specificationClass;
    @Option(names = "--spec-classpath", required = true)
    private String specificationClasspath;
    @Option(names = "--seller-field")
    private String sellerField;
    @Option(names = "--price-field")
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
            var loaded = DslContractLoader.load(projectDir, validator, purpose);
            var progress = VerificationProgress.console(System.out);
            progress.heading("Preparing typed DSL verification for " + validator + " ...");
            Path worker = loaded.project().resolve("build/verification-dsl-worker")
                    .resolve(validator);
            String workerClasspath = workerClasspath(
                    specificationClasspath, System.getProperty("java.class.path"));
            DslPropertySet candidate;
            try (var task = progress.start("Executing trusted Java property builder")) {
                candidate = new DslWorkerRunner().run(
                        workerClasspath, specificationClass, loaded.schema(), worker,
                        Duration.ofSeconds(workerTimeoutSeconds));
                task.succeed();
            }
            VerificationProperty property;
            try (var task = progress.start("Validating reviewed typed property")) {
                if (candidate.schemaVersion()
                        == DslPropertySet.COMPOSITION_SCHEMA_VERSION
                        || candidate.schemaVersion()
                        == DslPropertySet.TYPED_SCHEMA_VERSION
                        || candidate.schemaVersion()
                        == DslPropertySet.LEDGER_SCHEMA_VERSION
                        || candidate.schemaVersion()
                        == DslPropertySet.AUTHORIZATION_SCHEMA_VERSION
                        || candidate.schemaVersion()
                        == DslPropertySet.CERTIFICATE_PAYLOAD_SCHEMA_VERSION
                        || candidate.schemaVersion()
                        == DslPropertySet.VALUE_ALGEBRA_SCHEMA_VERSION) {
                    property = ComposedDslPromotion.promote(
                            candidate, loaded.schema(), validator, sourcePath);
                } else if (loaded.purpose() == VerificationPurpose.SPENDING) {
                    if (sellerField == null || priceField == null) {
                        throw new IllegalArgumentException(
                                "Spending DSL requires --seller-field and --price-field");
                    }
                    property = SellerPaymentDsl.resolve(candidate, loaded.schema(), validator,
                            sellerField, priceField, sourcePath);
                } else {
                    property = MintingDsl.resolve(
                            candidate, loaded.schema(), validator, sourcePath);
                }
                task.succeed(property.template());
            }
            Path output = outputDirectory == null
                    ? loaded.project().resolve("verification").resolve(loaded.artifactId())
                    : outputDirectory.toAbsolutePath().normalize();
            try (var task = progress.start("Generating hash-bound verification workspace")) {
                if (property instanceof SellerPaymentProperty payment) {
                    VerificationProjectGenerator.generateSellerPayment(
                            loaded.blueprint(), payment, fuel, recursiveDepth, output, force);
                } else if (property instanceof ControlledMintProperty controlled) {
                    VerificationProjectGenerator.generateControlledMint(
                            loaded.blueprint(), controlled, fuel, recursiveDepth, output, force);
                } else if (property instanceof OneShotMintProperty oneShot) {
                    VerificationProjectGenerator.generateOneShotMint(
                            loaded.blueprint(), oneShot,
                            fuel, recursiveDepth, output, force);
                } else {
                    VerificationProjectGenerator.generateComposedDsl(
                            loaded.blueprint(), (ComposedDslProperty) property,
                            loaded.schema(),
                            fuel, recursiveDepth, output, force);
                }
                task.succeed(output.toString());
            }
            progress.heading("Running verification ...");
            var execution = new VerificationRunner().run(output, backend, progress);
            System.out.println();
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

    static String normalizeClasspath(String classpath) {
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalArgumentException("Classpath must not be empty");
        }
        String[] entries = classpath.split(Pattern.quote(File.pathSeparator), -1);
        for (int index = 0; index < entries.length; index++) {
            String entry = entries[index].isEmpty() ? "." : entries[index];
            entries[index] = Path.of(entry).toAbsolutePath().normalize().toString();
        }
        return String.join(File.pathSeparator, entries);
    }

    static String workerClasspath(String specificationClasspath, String hostClasspath) {
        String specification = normalizeClasspath(specificationClasspath);
        if (hostClasspath == null || hostClasspath.isBlank()) {
            return specification;
        }
        return specification + File.pathSeparator + normalizeClasspath(hostClasspath);
    }
}
