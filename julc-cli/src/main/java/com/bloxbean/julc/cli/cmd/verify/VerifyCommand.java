package com.bloxbean.julc.cli.cmd.verify;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "verify",
        description = "Generate and run formal-verification workspaces",
        subcommands = {
                VerifyInitCommand.class,
                VerifyDslInitCommand.class,
                VerifyDslCommand.class,
                VerifyRunCommand.class,
                CommandLine.HelpCommand.class
        }
)
public class VerifyCommand implements Callable<Integer> {
    @Parameters(index = "0", defaultValue = ".",
            description = "JuLC project directory")
    private Path projectDir;

    @Option(names = "--validator",
            description = "Exact annotated Java validator title")
    private String validatorTitle;

    @Option(names = "--purpose",
            description = "Required for a multi-validator: ${COMPLETION-CANDIDATES}")
    private VerificationPurpose purpose;

    @Option(names = "--backend", defaultValue = "AUTO",
            description = "Execution backend: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
    private VerificationBackendKind backend;

    @Option(names = "--out-dir",
            description = "Generated workspace (default: <project>/verification/<artifact-id>)")
    private Path outputDirectory;

    @Option(names = "--fuel", defaultValue = "1000",
            description = "Positive Blaster preprocessing fuel")
    private int fuel;

    @Option(names = "--recursive-depth", defaultValue = "4",
            description = "Positive recursive schema decoding bound")
    private int recursiveDepth;

    @Option(names = "--force",
            description = "Regenerate generator-owned workspace files")
    private boolean force;

    @Override
    public Integer call() {
        if (validatorTitle == null || validatorTitle.isBlank()) {
            new CommandLine(this).usage(System.out);
            return 0;
        }
        try {
            var progress = VerificationProgress.console(System.out);
            var execution = new AnnotatedVerificationWorkflow().run(
                    projectDir, validatorTitle, purpose, backend, outputDirectory,
                    fuel, recursiveDepth, force, progress);
            var result = execution.run().result();
            System.out.println();
            System.out.println(result.outcome() + ": " + result.reason());
            System.out.println("Property: " + execution.property().template()
                    + " (" + execution.property().sourcePath() + ")");
            System.out.println("Workspace: " + execution.workspace());
            System.out.println("Certificate: "
                    + execution.workspace().resolve(VerificationRunner.RESULT_FILE));
            if (VerificationOutcome.parse(result.outcome()) == VerificationOutcome.REFUTED) {
                System.out.println("Counterexample: the exact validator accepted a context that "
                        + "violates " + execution.property().template() + " ("
                        + execution.property().sourcePath() + ").");
                execution.run().result().phases().stream()
                        .filter(phase -> "verify".equals(phase.phase()))
                        .filter(phase -> phase.exitCode() != null && phase.exitCode() == 3)
                        .map(phase -> execution.workspace().resolve(phase.log()))
                        .findFirst()
                        .ifPresent(path -> System.out.println("Raw Blaster model: " + path));
            }
            if (!execution.run().diagnostic().isBlank()) {
                System.err.println(execution.run().diagnostic());
            }
            return VerificationOutcome.parse(result.outcome()).exitCode();
        } catch (Exception e) {
            System.err.println("Verification failed: " + e.getMessage());
            return 1;
        }
    }
}
