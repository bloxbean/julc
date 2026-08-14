package com.bloxbean.julc.cli.cmd.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

interface VerificationProcess {

    ProcessResult execute(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment,
            Duration timeout,
            Path logFile) throws IOException, InterruptedException;

    record ProcessResult(int exitCode, boolean timedOut, String outputTail) {
    }

    final class SystemProcess implements VerificationProcess {
        private static final int MAX_TAIL_BYTES = 2 * 1024 * 1024;

        @Override
        public ProcessResult execute(
                List<String> command,
                Path workingDirectory,
                Map<String, String> environment,
                Duration timeout,
                Path logFile) throws IOException, InterruptedException {
            if (command == null || command.isEmpty()) {
                throw new IOException("Verification command must not be empty");
            }
            Files.createDirectories(logFile.getParent());
            Path temp = Files.createTempFile(logFile.getParent(), "." + logFile.getFileName(), ".tmp");
            try {
                var builder = new ProcessBuilder(command)
                        .directory(workingDirectory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(temp.toFile());
                builder.environment().putAll(environment);
                Process process = builder.start();
                boolean finished;
                try {
                    finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    terminate(process);
                    throw e;
                }
                if (!finished) {
                    terminate(process);
                }
                int exitCode = finished ? process.exitValue() : -1;
                try {
                    Files.move(temp, logFile, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, logFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return new ProcessResult(exitCode, !finished, readTail(logFile));
            } finally {
                Files.deleteIfExists(temp);
            }
        }

        private static void terminate(Process process) throws InterruptedException {
            List<ProcessHandle> descendants = process.descendants().toList();
            for (ProcessHandle child : descendants.reversed()) child.destroy();
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                for (ProcessHandle child : descendants.reversed()) {
                    if (child.isAlive()) child.destroyForcibly();
                }
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }

        private static String readTail(Path file) throws IOException {
            long size = Files.size(file);
            if (size <= MAX_TAIL_BYTES) return Files.readString(file);
            try (var channel = java.nio.channels.FileChannel.open(file)) {
                var buffer = java.nio.ByteBuffer.allocate(MAX_TAIL_BYTES);
                channel.position(size - MAX_TAIL_BYTES);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // continue
                }
                return new String(buffer.array(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }
}
