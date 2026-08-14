package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.julc.cli.project.ProjectLayout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "init",
        description = "Generate a pinned Blaster workspace from a built JuLC project")
public class VerifyInitCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Built JuLC project directory")
    private Path projectDir;

    @Option(names = "--validator", required = true,
            description = "Base Java validator title")
    private String validatorTitle;

    @Option(names = "--purpose", required = true,
            description = "Script purpose: ${COMPLETION-CANDIDATES}")
    private VerificationPurpose purpose;

    @Option(names = "--out-dir",
            description = "Generated workspace (default: <project>/verification/<artifact-id>)")
    private Path outputDir;

    @Option(names = "--fuel", defaultValue = "20000",
            description = "Positive Blaster preprocessing fuel")
    private int fuel;

    @Option(names = "--recursive-depth", defaultValue = "4",
            description = "Positive bound for recursive-domain verification experiments")
    private int recursiveDepth;

    @Option(names = "--force",
            description = "Overwrite generator-owned files in an existing workspace")
    private boolean force;

    @Override
    public Integer call() {
        try {
            Path project = projectDir.toAbsolutePath().normalize();
            Path blueprint = ProjectLayout.plutusDir(project).resolve("plutus.json");
            Path output = outputDir;
            if (output == null) {
                var metadata = com.bloxbean.julc.cli.cmd.blueprint.ArtifactCommand
                        .inspectForPurpose(blueprint, validatorTitle, purpose.cip57Name())
                        .artifact();
                output = project.resolve("verification").resolve(metadata.artifactId());
            }
            var result = VerificationProjectGenerator.generate(
                    blueprint, validatorTitle, purpose.userName(), fuel,
                    recursiveDepth, output, force);
            System.out.println("Generated verification workspace for " + validatorTitle
                    + " at " + result.outputDirectory());
            System.out.println("Initial result: COULD-NOT-EVALUATE (property-not-specialized)");
            return 0;
        } catch (UnsupportedVerificationException e) {
            System.err.println("COULD-NOT-EVALUATE: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("Verification workspace generation failed: " + e.getMessage());
            return 1;
        }
    }
}
