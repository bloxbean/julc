package com.bloxbean.julc.cli.cmd;

import com.bloxbean.julc.cli.check.TestDiscovery;
import com.bloxbean.julc.cli.check.TestReporter;
import com.bloxbean.julc.cli.check.TestResult;
import com.bloxbean.julc.cli.check.TestRunner;
import com.bloxbean.julc.cli.mcp.lint.LintEngine;
import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.output.AnsiColors;
import com.bloxbean.julc.cli.project.ProjectLayout;
import com.bloxbean.julc.cli.project.ProjectScanner;
import com.bloxbean.julc.cli.project.ProjectSourceResolver;
import com.bloxbean.julc.cli.project.TomlParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Command(name = "check", description = "Lint sources and run on-chain tests")
public class CheckCommand implements Runnable {

    @Parameters(index = "0", defaultValue = ".", description = "Project directory")
    private Path projectDir;

    @Option(names = "--no-lint", description = "Skip lint pre-check entirely")
    private boolean noLint;

    @Option(names = "--no-strict-lint",
            description = "Run lint but do not fail the command on error-level findings (lint becomes informational)")
    private boolean noStrictLint;

    @Override
    public void run() {
        System.exit(runCheck(projectDir, noLint, noStrictLint, System.out, System.err));
    }

    static int runCheck(Path projectDir, boolean noLint, boolean noStrictLint,
                        PrintStream out, PrintStream err) {
        try {
            Path root = projectDir.toAbsolutePath();
            Path tomlFile = ProjectLayout.tomlFile(root);
            if (!Files.exists(tomlFile)) {
                err.println(AnsiColors.red("Not a julc project (no julc.toml found)"));
                return 1;
            }

            var config = TomlParser.parse(tomlFile);
            out.println("Checking " + AnsiColors.bold(config.name()) + " ...\n");

            // Build library pool: stdlib + src/ files + test file sources
            var srcScan = ProjectScanner.scan(ProjectLayout.srcDir(root));
            var pool = ProjectSourceResolver.buildPool(srcScan.libraries());
            // Also add src/ validators as libraries (tests may reference them)
            pool.putAll(srcScan.validators());

            // Lint pass — runs across all .java sources in src/ and test/.
            boolean lintHadErrors = false;
            if (!noLint) {
                lintHadErrors = runLint(ProjectLayout.srcDir(root), ProjectLayout.testDir(root), out);
            }

            // Discover tests
            var tests = TestDiscovery.discover(ProjectLayout.testDir(root));
            boolean lintBlocks = lintHadErrors && !noStrictLint;
            if (tests.isEmpty()) {
                out.println(AnsiColors.yellow("No tests found in test/"));
                if (lintBlocks) {
                    printLintFailure(out);
                    return 1;
                }
                return 0;
            }

            // Run tests
            var runner = new TestRunner(pool);
            var results = new ArrayList<TestResult>();
            for (var test : tests) {
                // Add the test source file itself to the pool temporarily
                pool.put(test.className(), test.source());
                results.add(runner.run(test));
            }

            out.println();
            TestReporter.report(results, out);

            boolean allPassed = results.stream().allMatch(TestResult::passed);
            if (lintBlocks) {
                printLintFailure(out);
            }
            return (allPassed && !lintBlocks) ? 0 : 1;
        } catch (IOException e) {
            err.println(AnsiColors.red("Test error: " + e.getMessage()));
            return 1;
        }
    }

    /**
     * Runs the LintEngine across every {@code .java} source under {@code src/}
     * and {@code test/}. Prints a grouped report. Returns {@code true} iff at
     * least one error-level finding was produced (blocked by default unless
     * {@code --no-strict-lint} is set).
     */
    private static boolean runLint(Path srcDir, Path testDir, PrintStream out) throws IOException {
        var engine = new LintEngine();
        int totalFindings = 0;
        int totalErrors = 0;
        int filesWithFindings = 0;

        // Compose the (filename, source) tuple list we want to lint.
        List<Map.Entry<String, String>> sources = new ArrayList<>();
        collectJavaSources(sources, srcDir, "src");
        collectJavaSources(sources, testDir, "test");

        for (var entry : sources) {
            var findings = engine.lint(entry.getValue());
            if (findings.isEmpty()) continue;
            filesWithFindings++;
            out.println(AnsiColors.bold(entry.getKey()));
            for (var f : findings) {
                printFinding(f, out);
                totalFindings++;
                if ("error".equals(f.level())) totalErrors++;
            }
            out.println();
        }

        if (filesWithFindings == 0) {
            out.println(AnsiColors.green("✔ Lint clean (0 findings)") +
                    AnsiColors.dim(" — " + engine.rules().size() + " rules"));
            out.println();
        } else {
            String summary = String.format("Lint: %d finding(s) across %d file(s) (%d error(s))",
                    totalFindings, filesWithFindings, totalErrors);
            out.println(totalErrors > 0 ? AnsiColors.red(summary) : AnsiColors.yellow(summary));
            out.println();
        }

        return totalErrors > 0;
    }

    private static void collectJavaSources(List<Map.Entry<String, String>> sources,
                                           Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            sources.add(Map.entry(prefix + "/" + dir.relativize(p), Files.readString(p)));
                        } catch (IOException ignored) {
                            // Best-effort; lint never blocks the build for per-file IO errors here.
                        }
                    });
        }
    }

    private static void printFinding(LintFinding f, PrintStream out) {
        String levelTag = switch (f.level()) {
            case "error" -> AnsiColors.red("error");
            case "warning" -> AnsiColors.yellow("warning");
            default -> AnsiColors.dim("info");
        };
        String location = (f.line() > 0)
                ? AnsiColors.dim(":" + f.line() + ":" + f.column())
                : "";
        out.println("  " + levelTag + location + " [" + f.ruleId() + "] " + f.message());
        if (f.suggestion() != null && !f.suggestion().isEmpty()) {
            out.println("    " + AnsiColors.dim("→ " + f.suggestion()));
        }
    }

    private static void printLintFailure(PrintStream out) {
        out.println(AnsiColors.red("Check failed: lint produced error-level finding(s).")
                + AnsiColors.dim(" Use --no-strict-lint to make lint informational, or --no-lint to skip it."));
    }
}
