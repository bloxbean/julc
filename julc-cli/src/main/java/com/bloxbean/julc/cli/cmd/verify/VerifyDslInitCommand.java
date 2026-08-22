package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.verification.dsl.ContractMetamodelGenerator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "dsl-init",
        description = "Generate an experimental typed Java verification metamodel")
public final class VerifyDslInitCommand implements Callable<Integer> {
    @Parameters(index = "0", defaultValue = ".", description = "JuLC project directory")
    private Path projectDir;
    @Option(names = "--validator", required = true)
    private String validator;
    @Option(names = "--purpose",
            description = "Required for a multi-validator: ${COMPLETION-CANDIDATES}")
    private VerificationPurpose purpose;
    @Option(names = "--package", required = true)
    private String packageName;
    @Option(names = "--class", required = true)
    private String className;
    @Option(names = "--schema-version", defaultValue = "3",
            description = "Experimental DSL schema to generate: 3, 4, 5, or 6")
    private int schemaVersion;
    @Option(names = "--out", required = true,
            description = "Generated .java file (refuses to overwrite)")
    private Path output;

    @Override
    public Integer call() {
        try {
            Path target = output.toAbsolutePath().normalize();
            if (Files.exists(target)) {
                throw new IllegalArgumentException(
                        "Refusing to overwrite generated metamodel: " + target);
            }
            var loaded = DslContractLoader.load(projectDir, validator, purpose);
            String source = switch (schemaVersion) {
                case 3 -> ContractMetamodelGenerator.generate(
                        loaded.schema(), packageName, className);
                case 4 -> ContractMetamodelGenerator.generateTypedV4(
                        loaded.schema(), packageName, className);
                case 5 -> ContractMetamodelGenerator.generateTypedV5(
                        loaded.schema(), packageName, className);
                case 6 -> ContractMetamodelGenerator.generateTypedV6(
                        loaded.schema(), packageName, className);
                default -> throw new IllegalArgumentException(
                        "DSL metamodel schema version must be 3, 4, 5, or 6");
            };
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, source);
            System.out.println("Generated experimental DSL metamodel: " + target);
            System.out.println("Trusted-source boundary: compiling/running a DSL specification "
                    + "executes project Java in a bounded worker.");
            return 0;
        } catch (Exception e) {
            System.err.println("DSL metamodel generation failed: " + e.getMessage());
            return 1;
        }
    }
}
