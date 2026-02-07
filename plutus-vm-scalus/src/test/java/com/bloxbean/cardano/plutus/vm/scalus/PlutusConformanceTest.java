package com.bloxbean.cardano.plutus.vm.scalus;

import com.bloxbean.cardano.plutus.core.*;
import com.bloxbean.cardano.plutus.core.text.UplcParser;
import com.bloxbean.cardano.plutus.core.text.UplcPrinter;
import com.bloxbean.cardano.plutus.vm.EvalResult;
import com.bloxbean.cardano.plutus.vm.PlutusLanguage;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Runs the official Plutus conformance test suite against the Scalus VM backend.
 * <p>
 * Test cases are from the IntersectMBO/plutus repository
 * (plutus-conformance/test-cases/uplc/evaluation/).
 * <p>
 * Each test case has:
 * <ul>
 *   <li>{name}.uplc — input UPLC program in text format</li>
 *   <li>{name}.uplc.expected — expected result: either "evaluation failure",
 *       "parse error", or a UPLC program in text format</li>
 * </ul>
 */
class PlutusConformanceTest {

    private static final ScalusVmProvider PROVIDER = new ScalusVmProvider();

    /**
     * V4/future builtins not supported by our V3-targeting implementation.
     * Tests referencing these builtins or the 'value'/'array' types are skipped.
     */
    /**
     * V4/future builtins not supported by our V3-targeting implementation.
     * Tests referencing these builtins or the 'value'/'array' types are skipped.
     */
    private static final Set<String> SKIP_DIRS = Set.of(
            // V4 builtins
            "dropList", "lengthOfArray", "listToArray", "indexArray",
            "bls12_381_G1_multiScalarMul", "bls12_381_G2_multiScalarMul",
            "insertCoin", "lookupCoin", "unionValue", "valueContains",
            "valueData", "unValueData", "scaleValue", "multiIndexArray",
            // V4 types (constant tests)
            "array", "value",
            // Case-on-constant for built-in types: Scalus 0.15.0 doesn't support
            // scrutinizing built-in values (Bool, Integer, List, Pair, Unit) via case.
            // The Plutus reference impl added this feature, but Scalus hasn't yet.
            "constant-case"
    );

    /**
     * Path substrings that trigger skipping (for patterns that don't fit the /dir/ check).
     * BLS12-381: Scalus FLAT codec doesn't support encoding/decoding BLS constant type tags,
     * so all BLS tests must be skipped when using the FLAT bridge.
     */
    private static final String[] SKIP_PATH_CONTAINS = {
            "bls12-381", "bls12_381"
    };

    @TestFactory
    Stream<DynamicTest> plutusConformanceTests() throws IOException, URISyntaxException {
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

                        String testName = getTestName(conformanceDir, uplcFile);

                        if (shouldSkip(uplcFile)) {
                            testCases.add(DynamicTest.dynamicTest("[SKIP] " + testName,
                                    () -> org.junit.jupiter.api.Assumptions.assumeTrue(false,
                                            "Skipped — see SKIP_DIRS/SKIP_PATH_CONTAINS")));
                            return;
                        }

                        testCases.add(DynamicTest.dynamicTest(testName,
                                () -> runConformanceTest(uplcFile, expectedFile)));
                    });
        }
        return testCases.stream();
    }

    private void runConformanceTest(Path uplcFile, Path expectedFile) throws IOException {
        String input = Files.readString(uplcFile).trim();
        String expected = Files.readString(expectedFile).trim();

        // Skip tests with V4 'value' or 'array' type in expected output
        if (expected.contains("con value") || expected.contains("con array")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "V4 type in expected output — skipped");
            return;
        }

        // Step 1: Try to parse the input
        Program program;
        try {
            program = UplcParser.parseProgram(input);
        } catch (Exception e) {
            if ("parse error".equals(expected)) {
                return; // Parse error expected — PASS
            }
            throw new AssertionError(
                    "Unexpected parse error: " + e.getMessage() + "\nInput: " + input, e);
        }

        // If we parsed successfully but expected parse error
        if ("parse error".equals(expected)) {
            throw new AssertionError(
                    "Expected parse error but parsing succeeded.\nInput: " + input);
        }

        // Step 2: Evaluate the program
        PlutusLanguage language = detectLanguage(program);
        EvalResult result = PROVIDER.evaluate(program, language, null);

        // Step 3: Compare against expected
        if ("evaluation failure".equals(expected)) {
            if (result.isSuccess()) {
                var success = (EvalResult.Success) result;
                throw new AssertionError(
                        "Expected evaluation failure but got success.\n" +
                        "Result: " + UplcPrinter.print(success.resultTerm()) +
                        "\nInput: " + input);
            }
            return; // Evaluation failure expected — PASS
        }

        // Expected a successful result
        if (!result.isSuccess()) {
            var failure = (EvalResult.Failure) result;
            throw new AssertionError(
                    "Expected success but got evaluation failure.\n" +
                    "Error: " + failure.error() +
                    "\nExpected: " + expected +
                    "\nInput: " + input);
        }

        // Step 4: Compare result term to expected
        var success = (EvalResult.Success) result;
        Program actualProgram = new Program(program.major(), program.minor(), program.patch(),
                success.resultTerm());
        String actualOutput = UplcPrinter.print(actualProgram);

        // Try structural comparison (parse expected, compare ASTs)
        try {
            Program expectedProgram = UplcParser.parseProgram(expected);
            if (!termsEqual(expectedProgram.term(), actualProgram.term())) {
                throw new AssertionError(
                        "Result mismatch (structural).\n" +
                        "Expected: " + expected + "\n" +
                        "Actual:   " + actualOutput + "\n" +
                        "Input:    " + input);
            }
        } catch (Exception parseEx) {
            // Can't parse expected output (e.g., uses V4 types) — fall back to normalized string comparison
            String normalizedExpected = normalizeWhitespace(expected);
            String normalizedActual = normalizeWhitespace(actualOutput);
            if (!normalizedExpected.equals(normalizedActual)) {
                throw new AssertionError(
                        "Result mismatch (string).\n" +
                        "Expected: " + expected + "\n" +
                        "Actual:   " + actualOutput + "\n" +
                        "Input:    " + input);
            }
        }
    }

    /**
     * Structurally compare two terms, ignoring variable names (alpha-equivalence).
     */
    private boolean termsEqual(Term a, Term b) {
        return switch (a) {
            case Term.Var va -> b instanceof Term.Var vb
                    && va.name().index() == vb.name().index();
            case Term.Lam la -> b instanceof Term.Lam lb
                    && termsEqual(la.body(), lb.body());
            case Term.Apply aa -> b instanceof Term.Apply ab
                    && termsEqual(aa.function(), ab.function())
                    && termsEqual(aa.argument(), ab.argument());
            case Term.Force fa -> b instanceof Term.Force fb
                    && termsEqual(fa.term(), fb.term());
            case Term.Delay da -> b instanceof Term.Delay db
                    && termsEqual(da.term(), db.term());
            case Term.Const ca -> b instanceof Term.Const cb
                    && constantsEqual(ca.value(), cb.value());
            case Term.Builtin ba -> b instanceof Term.Builtin bb
                    && ba.fun() == bb.fun();
            case Term.Error _ -> b instanceof Term.Error;
            case Term.Constr ca -> b instanceof Term.Constr cb
                    && ca.tag() == cb.tag()
                    && ca.fields().size() == cb.fields().size()
                    && listsEqual(ca.fields(), cb.fields());
            case Term.Case csa -> b instanceof Term.Case csb
                    && termsEqual(csa.scrutinee(), csb.scrutinee())
                    && csa.branches().size() == csb.branches().size()
                    && listsEqual(csa.branches(), csb.branches());
        };
    }

    private boolean listsEqual(List<Term> a, List<Term> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!termsEqual(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private boolean constantsEqual(Constant a, Constant b) {
        return switch (a) {
            case Constant.IntegerConst ia -> b instanceof Constant.IntegerConst ib
                    && ia.value().equals(ib.value());
            case Constant.ByteStringConst bsa -> b instanceof Constant.ByteStringConst bsb
                    && Arrays.equals(bsa.value(), bsb.value());
            case Constant.StringConst sa -> b instanceof Constant.StringConst sb
                    && sa.value().equals(sb.value());
            case Constant.UnitConst _ -> b instanceof Constant.UnitConst;
            case Constant.BoolConst ba -> b instanceof Constant.BoolConst bb
                    && ba.value() == bb.value();
            case Constant.DataConst da -> b instanceof Constant.DataConst db
                    && dataEqual(da.value(), db.value());
            case Constant.ListConst la -> b instanceof Constant.ListConst lb
                    && la.values().size() == lb.values().size()
                    && constantListsEqual(la.values(), lb.values());
            case Constant.PairConst pa -> b instanceof Constant.PairConst pb
                    && constantsEqual(pa.first(), pb.first())
                    && constantsEqual(pa.second(), pb.second());
            case Constant.Bls12_381_G1Element g1a -> b instanceof Constant.Bls12_381_G1Element g1b
                    && Arrays.equals(g1a.value(), g1b.value());
            case Constant.Bls12_381_G2Element g2a -> b instanceof Constant.Bls12_381_G2Element g2b
                    && Arrays.equals(g2a.value(), g2b.value());
            case Constant.Bls12_381_MlResult mla -> b instanceof Constant.Bls12_381_MlResult mlb
                    && Arrays.equals(mla.value(), mlb.value());
        };
    }

    private boolean constantListsEqual(List<Constant> a, List<Constant> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!constantsEqual(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private boolean dataEqual(PlutusData a, PlutusData b) {
        return switch (a) {
            case PlutusData.IntData ia -> b instanceof PlutusData.IntData ib
                    && ia.value().equals(ib.value());
            case PlutusData.BytesData bsa -> b instanceof PlutusData.BytesData bsb
                    && Arrays.equals(bsa.value(), bsb.value());
            case PlutusData.Constr ca -> b instanceof PlutusData.Constr cb
                    && ca.tag() == cb.tag()
                    && ca.fields().size() == cb.fields().size()
                    && dataListsEqual(ca.fields(), cb.fields());
            case PlutusData.ListData la -> b instanceof PlutusData.ListData lb
                    && la.items().size() == lb.items().size()
                    && dataListsEqual(la.items(), lb.items());
            case PlutusData.Map ma -> b instanceof PlutusData.Map mb
                    && ma.entries().size() == mb.entries().size()
                    && mapEntriesEqual(ma.entries(), mb.entries());
        };
    }

    private boolean dataListsEqual(List<PlutusData> a, List<PlutusData> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!dataEqual(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private boolean mapEntriesEqual(List<PlutusData.Pair> a, List<PlutusData.Pair> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!dataEqual(a.get(i).key(), b.get(i).key())) return false;
            if (!dataEqual(a.get(i).value(), b.get(i).value())) return false;
        }
        return true;
    }

    private PlutusLanguage detectLanguage(Program program) {
        // V1 uses 1.0.0, V2 also uses 1.0.0, V3 uses 1.1.0
        // Conformance tests use version to signal language
        if (program.major() == 1 && program.minor() >= 1) {
            return PlutusLanguage.PLUTUS_V3;
        }
        // Default to V3 for 1.0.0 (most builtins available)
        return PlutusLanguage.PLUTUS_V3;
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

    private String getTestName(Path base, Path uplcFile) {
        return base.relativize(uplcFile).toString()
                .replace(".uplc", "")
                .replace('/', '.');
    }

    private String normalizeWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
