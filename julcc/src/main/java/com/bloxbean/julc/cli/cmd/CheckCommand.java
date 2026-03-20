package com.bloxbean.julc.cli.cmd;

import com.bloxbean.julc.cli.check.TestDiscovery;
import com.bloxbean.julc.cli.check.TestReporter;
import com.bloxbean.julc.cli.check.TestResult;
import com.bloxbean.julc.cli.check.TestRunner;
import com.bloxbean.julc.cli.output.AnsiColors;
import com.bloxbean.julc.cli.project.ProjectLayout;
import com.bloxbean.julc.cli.project.ProjectScanner;
import com.bloxbean.julc.cli.project.ProjectSourceResolver;
import com.bloxbean.julc.cli.project.TomlParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

@Command(name = "check", description = "Run on-chain tests")
public class CheckCommand implements Runnable {

    @Parameters(index = "0", defaultValue = ".", description = "Project directory")
    private Path projectDir;

    @Override
    public void run() {
        try {
            Path root = projectDir.toAbsolutePath();
            Path tomlFile = ProjectLayout.tomlFile(root);
            if (!Files.exists(tomlFile)) {
                System.err.println(AnsiColors.red("Not a julcc project (no julc.toml found)"));
                System.exit(1);
            }

            var config = TomlParser.parse(tomlFile);
            System.out.println("Testing " + AnsiColors.bold(config.name()) + " ...\n");

            // Discover tests
            var tests = TestDiscovery.discover(ProjectLayout.testDir(root));
            if (tests.isEmpty()) {
                System.out.println(AnsiColors.yellow("No tests found in test/"));
                return;
            }

            // Build library pool: stdlib + src/ files + test file sources
            var srcScan = ProjectScanner.scan(ProjectLayout.srcDir(root));
            var pool = ProjectSourceResolver.buildPool(srcScan.libraries());
            // Also add src/ validators as libraries (tests may reference them)
            pool.putAll(srcScan.validators());

            // Run tests
            var runner = new TestRunner(pool);
            var results = new ArrayList<TestResult>();
            for (var test : tests) {
                // Add the test source file itself to the pool temporarily
                pool.put(test.className(), test.source());
                results.add(runner.run(test));
            }

            System.out.println();
            TestReporter.report(results, System.out);

            boolean allPassed = results.stream().allMatch(TestResult::passed);
            System.exit(allPassed ? 0 : 1);
        } catch (IOException e) {
            System.err.println(AnsiColors.red("Test error: " + e.getMessage()));
            System.exit(1);
        }
    }
}
