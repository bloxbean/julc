package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.text.UplcParser;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.JavaVmProvider;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Cross-validates the Truffle backend against the Java backend.
 * <p>
 * For every conformance test that passes on both backends, asserts:
 * <ul>
 *   <li>Same success/failure outcome</li>
 *   <li>Same result term (structural equality)</li>
 *   <li>Same trace messages</li>
 * </ul>
 */
class CrossValidationTest {

    private static final TruffleVmProvider TRUFFLE = new TruffleVmProvider();
    private static final JavaVmProvider JAVA = new JavaVmProvider();

    private static final Set<String> SKIP_DIRS = Set.of(
            "dropList", "lengthOfArray", "listToArray", "indexArray",
            "bls12_381_G1_multiScalarMul", "bls12_381_G2_multiScalarMul",
            "insertCoin", "lookupCoin", "unionValue", "valueContains",
            "valueData", "unValueData", "scaleValue", "multiIndexArray",
            "array", "value"
    );

    private static final String[] SKIP_PATH_CONTAINS = {
            "bls12-381", "bls12_381"
    };

    @TestFactory
    Stream<DynamicTest> crossValidation() throws IOException, URISyntaxException {
        var conformanceDir = Paths.get(
                Objects.requireNonNull(getClass().getResource("/conformance")).toURI());

        var testCases = new ArrayList<DynamicTest>();
        try (var walk = Files.walk(conformanceDir)) {
            walk.filter(p -> p.toString().endsWith(".uplc"))
                    .filter(p -> !p.toString().endsWith(".expected"))
                    .sorted()
                    .forEach(uplcFile -> {
                        var expectedFile = Paths.get(uplcFile + ".expected");
                        if (!Files.exists(expectedFile)) return;

                        String testName = conformanceDir.relativize(uplcFile).toString()
                                .replace(".uplc", "").replace('/', '.');
                        if (shouldSkip(uplcFile)) return;

                        testCases.add(DynamicTest.dynamicTest(testName,
                                () -> runCrossValidation(uplcFile, expectedFile)));
                    });
        }
        return testCases.stream();
    }

    private void runCrossValidation(Path uplcFile, Path expectedFile) throws IOException {
        String input = Files.readString(uplcFile).trim();
        String expected = Files.readString(expectedFile).trim();

        if (expected.contains("con value") || expected.contains("con array")) return;

        Program program;
        try {
            program = UplcParser.parseProgram(input);
        } catch (Exception e) {
            return; // Parse error — skip cross-validation
        }
        if ("parse error".equals(expected)) return;

        PlutusLanguage language = PlutusLanguage.PLUTUS_V3;

        EvalResult truffleResult = TRUFFLE.evaluate(program, language, null);
        EvalResult javaResult = JAVA.evaluate(program, language, null);

        // Same outcome type
        if (truffleResult.isSuccess() != javaResult.isSuccess()) {
            throw new AssertionError(
                    "Outcome mismatch.\n" +
                    "Truffle: " + describeResult(truffleResult) + "\n" +
                    "Java:    " + describeResult(javaResult) + "\n" +
                    "Input:   " + input);
        }

        // Same trace messages
        if (!truffleResult.traces().equals(javaResult.traces())) {
            throw new AssertionError(
                    "Trace mismatch.\n" +
                    "Truffle traces: " + truffleResult.traces() + "\n" +
                    "Java traces:    " + javaResult.traces() + "\n" +
                    "Input: " + input);
        }

        if (truffleResult.isSuccess()) {
            var ts = (EvalResult.Success) truffleResult;
            var js = (EvalResult.Success) javaResult;

            String trufflePrinted = UplcPrinter.print(ts.resultTerm());
            String javaPrinted = UplcPrinter.print(js.resultTerm());

            if (!normalizeWhitespace(trufflePrinted).equals(normalizeWhitespace(javaPrinted))) {
                throw new AssertionError(
                        "Result mismatch.\n" +
                        "Truffle: " + trufflePrinted + "\n" +
                        "Java:    " + javaPrinted + "\n" +
                        "Input:   " + input);
            }
        }
    }

    private static String describeResult(EvalResult r) {
        return switch (r) {
            case EvalResult.Success s -> "Success: " + UplcPrinter.print(s.resultTerm());
            case EvalResult.Failure f -> "Failure: " + f.error();
            case EvalResult.BudgetExhausted b -> "BudgetExhausted";
        };
    }

    private boolean shouldSkip(Path uplcFile) {
        String pathStr = uplcFile.toString();
        for (String skipDir : SKIP_DIRS) {
            if (pathStr.contains("/" + skipDir + "/")) return true;
        }
        for (String substring : SKIP_PATH_CONTAINS) {
            if (pathStr.contains(substring)) return true;
        }
        return false;
    }

    private String normalizeWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
