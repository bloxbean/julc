package com.bloxbean.julc.cli.cmd.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

interface VerificationExecutionBackend {

    BackendContext prepare(
            Path workspace,
            VerificationProcess process,
            Duration timeout,
            Path logFile) throws IOException, InterruptedException;

    List<String> command(List<String> workspaceCommand, Path workspace, boolean offline)
            throws IOException;

    Map<String, String> environment(BackendContext context, boolean offline);

    String name();

    record BackendContext(String identity, Map<String, String> values) {
    }
}
