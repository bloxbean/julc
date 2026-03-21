package com.bloxbean.julc.cli.check;

import com.bloxbean.julc.cli.output.AnsiColors;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aiken-style colored terminal output for test results.
 */
public final class TestReporter {

    private TestReporter() {}

    public static void report(List<TestResult> results, PrintStream out) {
        // Group by class
        Map<String, List<TestResult>> byClass = results.stream()
                .collect(Collectors.groupingBy(TestResult::className,
                        java.util.LinkedHashMap::new, Collectors.toList()));

        for (var entry : byClass.entrySet()) {
            String className = entry.getKey();
            List<TestResult> classResults = entry.getValue();

            out.println("  " + AnsiColors.BOLD + "\u2535\u2501 " + className
                    + " " + "\u2501".repeat(Math.max(1, 50 - className.length())) + AnsiColors.RESET);

            for (var r : classResults) {
                String status = r.passed()
                        ? AnsiColors.green("PASS")
                        : AnsiColors.red("FAIL");
                String budget = String.format("[mem: %6d, cpu: %10d]",
                        r.budget().memoryUnits(), r.budget().cpuSteps());
                out.println("  \u2502 " + status + " " + AnsiColors.dim(budget) + " " + r.methodName());

                if (!r.passed() && r.traces() != null && !r.traces().isEmpty()) {
                    out.println("  \u2502  \u00b7 with traces");
                    for (String trace : r.traces()) {
                        out.println("  \u2502  | " + trace);
                    }
                }
                if (!r.passed() && r.error() != null) {
                    out.println("  \u2502  \u00b7 " + AnsiColors.red(r.error()));
                }
            }

            long passed = classResults.stream().filter(TestResult::passed).count();
            long failed = classResults.size() - passed;
            out.println("  " + AnsiColors.BOLD + "\u2535" + "\u2501".repeat(30)
                    + " " + classResults.size() + " tests | "
                    + AnsiColors.green(passed + " passed") + " | "
                    + (failed > 0 ? AnsiColors.red(failed + " failed") : AnsiColors.dim("0 failed"))
                    + AnsiColors.RESET);
            out.println();
        }

        // Summary
        long totalPassed = results.stream().filter(TestResult::passed).count();
        long totalFailed = results.size() - totalPassed;
        if (totalFailed == 0) {
            out.println(AnsiColors.green("All " + results.size() + " tests passed."));
        } else {
            out.println(AnsiColors.red(totalFailed + " of " + results.size() + " tests failed."));
        }
    }
}
