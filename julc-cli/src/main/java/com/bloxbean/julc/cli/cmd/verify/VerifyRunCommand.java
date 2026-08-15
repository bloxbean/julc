package com.bloxbean.julc.cli.cmd.verify;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "run",
        description = "Run a pinned JuLC verification workspace")
public class VerifyRunCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".",
            description = "Trusted verification workspace directory")
    private Path workspace;

    @Option(names = "--backend", defaultValue = "AUTO",
            description = "Execution backend: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
    private VerificationBackendKind backend;

    @Override
    public Integer call() {
        try {
            var progress = VerificationProgress.console(System.out);
            progress.heading("Running verification ...");
            var execution = new VerificationRunner().run(workspace, backend, progress);
            var result = execution.result();
            System.out.println();
            System.out.println(result.outcome() + ": " + result.reason());
            System.out.println("Backend: " + result.backend() + " (" + result.backendIdentity() + ")");
            System.out.println("Result: " + workspace.toAbsolutePath().normalize()
                    .resolve(VerificationRunner.RESULT_FILE));
            if (!execution.diagnostic().isBlank()) {
                System.err.println(execution.diagnostic());
            }
            return VerificationOutcome.parse(result.outcome()).exitCode();
        } catch (Exception e) {
            System.err.println("Verification runner failed: " + e.getMessage());
            return 1;
        }
    }
}
