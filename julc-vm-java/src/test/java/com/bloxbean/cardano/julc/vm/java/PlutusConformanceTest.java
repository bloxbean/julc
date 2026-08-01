package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.text.UplcParser;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureRegistry;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Runs the official Plutus conformance test suite against the pure Java CEK machine.
 * <p>
 * Same test structure as the Scalus backend conformance tests.
 */
class PlutusConformanceTest {

    private static final JavaVmProvider PROVIDER = new JavaVmProvider();

    private static final int EXPECTED_CASE_COUNT = 999;
    private static final int EXPECTED_BASE_NUMERIC_BUDGETS = 545;
    private static final int EXPECTED_PV11_NUMERIC_BUDGETS = 724;
    private static final int EXPECTED_PV10_APPLICABLE_CASES = 737;
    private static final int EXPECTED_PV11_OVERRIDES = 65;
    private static final int EXPECTED_PV11_BUDGET_OVERRIDES = 61;
    private static final int EXPECTED_PV11_RESULT_OVERRIDES = 4;

    /** Matches the conformance suite's budget files: ({cpu: 123 | mem: 45}). */
    private static final java.util.regex.Pattern BUDGET_PATTERN =
            java.util.regex.Pattern.compile("cpu:\\s*(\\d+)\\s*\\|\\s*mem:\\s*(\\d+)");

    /** Builtins/types not yet supported. */
    private static final Set<String> SKIP_DIRS = Set.of(
    );

    /** Path substrings that trigger skipping. */
    private static final String[] SKIP_PATH_CONTAINS = {
    };

    /**
     * Directories whose {@code .budget.expected} files are not compared.
     * The suite's budgets are generated with the latest Plutus default cost
     * model; entries here differ from the PV10 mainnet defaults this VM uses.
     */
    private static final Set<String> SKIP_BUDGET_DIRS = Set.of(
    );

    @TestFactory
    Stream<DynamicTest> plutusConformanceTests() throws IOException, URISyntaxException {
        var conformanceDir = Paths.get(
                Objects.requireNonNull(getClass().getResource("/conformance")).toURI());
        var pv11OverlayDir = Paths.get(
                Objects.requireNonNull(getClass().getResource("/conformance-pv11")).toURI());

        var uplcFiles = findInputFiles(conformanceDir);
        verifyCorpusProvenance(conformanceDir, pv11OverlayDir, uplcFiles);

        var profiles = List.of(
                new ConformanceProfile("V3/PV10/C",
                        LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3),
                        null,
                        EXPECTED_PV10_APPLICABLE_CASES,
                        EXPECTED_BASE_NUMERIC_BUDGETS),
                new ConformanceProfile("V3/PV11/E",
                        LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                        pv11OverlayDir,
                        EXPECTED_CASE_COUNT,
                        EXPECTED_PV11_NUMERIC_BUDGETS));

        var testCases = new ArrayList<DynamicTest>();
        for (var profile : profiles) {
            int numericBudgetCount = countNumericBudgetComparisons(
                    conformanceDir, profile, uplcFiles);
            requireCount(profile.expectedNumericBudgets(), numericBudgetCount,
                    profile.name() + " numeric budget comparisons");

            int applicableCases = 0;
            for (var uplcFile : uplcFiles) {
                var relative = conformanceDir.relativize(uplcFile);
                var expectedFile = selectExpectation(
                        conformanceDir, profile.overlayDir(), relative + ".expected");
                var budgetFile = selectExpectation(
                        conformanceDir, profile.overlayDir(), relative + ".budget.expected");
                String testName = "[" + profile.name() + "] "
                        + getTestName(conformanceDir, uplcFile);

                if (!isApplicable(uplcFile, profile.target())) {
                    testCases.add(DynamicTest.dynamicTest("[UNAVAILABLE] " + testName,
                            () -> org.junit.jupiter.api.Assumptions.assumeTrue(false,
                                    "The program uses a builtin unavailable in this ledger profile")));
                    continue;
                }
                applicableCases++;

                if (shouldSkip(uplcFile)) {
                    testCases.add(DynamicTest.dynamicTest("[SKIP] " + testName,
                            () -> org.junit.jupiter.api.Assumptions.assumeTrue(false,
                                    "Skipped — see SKIP_DIRS/SKIP_PATH_CONTAINS")));
                    continue;
                }

                testCases.add(DynamicTest.dynamicTest(testName,
                        () -> runConformanceTest(
                                uplcFile, expectedFile, budgetFile, profile.target())));
            }
            requireCount(profile.expectedApplicableCases(), applicableCases,
                    profile.name() + " applicable conformance cases");
        }
        requireCount(EXPECTED_CASE_COUNT * profiles.size(), testCases.size(),
                "profiled conformance tests");
        return testCases.stream();
    }

    private void runConformanceTest(
            Path uplcFile,
            Path expectedFile,
            Path budgetFile,
            LedgerEvaluationTarget target) throws IOException {
        String input = Files.readString(uplcFile).trim();
        String expected = Files.readString(expectedFile).trim();

        // Step 1: Parse input
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

        if ("parse error".equals(expected)) {
            throw new AssertionError(
                    "Expected parse error but parsing succeeded.\nInput: " + input);
        }

        // Step 2: Evaluate
        EvalResult result = PROVIDER.evaluate(program, target, null);

        // Step 3: Compare
        if ("evaluation failure".equals(expected)) {
            if (result.isSuccess()) {
                var success = (EvalResult.Success) result;
                throw new AssertionError(
                        "Expected evaluation failure but got success.\n" +
                        "Result: " + UplcPrinter.print(success.resultTerm()) +
                        "\nInput: " + input);
            }
            return;
        }

        if (!result.isSuccess()) {
            var failure = (EvalResult.Failure) result;
            throw new AssertionError(
                    "Expected success but got evaluation failure.\n" +
                    "Error: " + failure.error() +
                    "\nExpected: " + expected +
                    "\nInput: " + input);
        }

        // Step 4: Compare result
        var success = (EvalResult.Success) result;
        Program actualProgram = new Program(program.major(), program.minor(), program.patch(),
                success.resultTerm());
        String actualOutput = UplcPrinter.print(actualProgram);

        try {
            Program expectedProgram = UplcParser.parseProgram(expected);
            if (!termsEqual(expectedProgram.term(), actualProgram.term())) {
                throw new AssertionError(
                        "Result mismatch (structural).\n" +
                        "Expected: " + expected + "\n" +
                        "Actual:   " + actualOutput + "\n" +
                        "Input:    " + input);
            }
        } catch (AssertionError ae) {
            throw ae;
        } catch (Exception parseEx) {
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

        // Step 5: Compare budget when the suite provides one
        verifyBudget(uplcFile, budgetFile, result, input);
    }

    private void verifyBudget(
            Path uplcFile, Path budgetFile, EvalResult result, String input) throws IOException {
        for (String skipDir : SKIP_BUDGET_DIRS) {
            if (uplcFile.toString().contains("/" + skipDir + "/")) return;
        }

        String budgetExpected = Files.readString(budgetFile).trim();
        var matcher = BUDGET_PATTERN.matcher(budgetExpected);
        if (!matcher.find()) {
            if ("evaluation failure".equals(budgetExpected) || "parse error".equals(budgetExpected)) {
                return;
            }
            // Fail loudly on format drift — a silently skipped budget assertion
            // is exactly the blind spot this check exists to close
            throw new AssertionError(
                    "Unrecognized budget file format: " + budgetFile + "\nContent: " + budgetExpected);
        }

        long expectedCpu = Long.parseLong(matcher.group(1));
        long expectedMem = Long.parseLong(matcher.group(2));
        var consumed = result.budgetConsumed();
        if (consumed.cpuSteps() != expectedCpu || consumed.memoryUnits() != expectedMem) {
            throw new AssertionError(
                    "Budget mismatch.\n" +
                    "Expected: cpu=" + expectedCpu + ", mem=" + expectedMem + "\n" +
                    "Actual:   cpu=" + consumed.cpuSteps() + ", mem=" + consumed.memoryUnits() + "\n" +
                    "Input:    " + input);
        }
    }

    private List<Path> findInputFiles(Path conformanceDir) throws IOException {
        try (var walk = Files.walk(conformanceDir)) {
            return walk.filter(p -> p.toString().endsWith(".uplc"))
                    .filter(p -> !p.toString().endsWith(".expected"))
                    .sorted()
                    .toList();
        }
    }

    private void verifyCorpusProvenance(
            Path conformanceDir, Path pv11OverlayDir, List<Path> uplcFiles) throws IOException {
        requireCount(EXPECTED_CASE_COUNT, uplcFiles.size(), "base input cases");

        var overlayFiles = new ArrayList<Path>();
        try (var walk = Files.walk(pv11OverlayDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("README.md"))
                    .sorted()
                    .forEach(overlayFiles::add);
        }
        requireCount(EXPECTED_PV11_OVERRIDES, overlayFiles.size(), "PV11 overlay files");
        requireCount(EXPECTED_PV11_BUDGET_OVERRIDES,
                overlayFiles.stream()
                        .filter(p -> p.toString().endsWith(".budget.expected"))
                        .count(),
                "PV11 budget overrides");
        requireCount(EXPECTED_PV11_RESULT_OVERRIDES,
                overlayFiles.stream()
                        .filter(p -> p.toString().endsWith(".uplc.expected"))
                        .count(),
                "PV11 result overrides");

        for (var overlayFile : overlayFiles) {
            var baseFile = conformanceDir.resolve(pv11OverlayDir.relativize(overlayFile));
            if (!Files.isRegularFile(baseFile)) {
                throw new AssertionError("PV11 override has no base counterpart: " + overlayFile);
            }
            if (Files.readString(baseFile).trim().equals(Files.readString(overlayFile).trim())) {
                throw new AssertionError("PV11 override does not change its base file: " + overlayFile);
            }
        }
    }

    private int countNumericBudgetComparisons(
            Path conformanceDir, ConformanceProfile profile, List<Path> uplcFiles) throws IOException {
        int count = 0;
        for (var uplcFile : uplcFiles) {
            if (!isApplicable(uplcFile, profile.target())) {
                continue;
            }
            var relative = conformanceDir.relativize(uplcFile);
            var budgetFile = selectExpectation(
                    conformanceDir, profile.overlayDir(), relative + ".budget.expected");
            if (BUDGET_PATTERN.matcher(Files.readString(budgetFile)).find()) {
                count++;
            }
        }
        return count;
    }

    private boolean isApplicable(Path uplcFile, LedgerEvaluationTarget target) throws IOException {
        try {
            var program = UplcParser.parseProgram(Files.readString(uplcFile).trim());
            var profile = ProtocolFeatureRegistry.resolve(target);
            return isApplicable(program.term(), profile.availableBuiltins(),
                    profile.caseOnBuiltinConstants());
        } catch (Exception ignored) {
            // Parse-error fixtures still belong to both profiles and must be asserted.
            return true;
        }
    }

    private boolean isApplicable(
            Term term, Set<DefaultFun> available, boolean caseOnBuiltinConstants) {
        return switch (term) {
            case Term.Var _ -> true;
            case Term.Lam lam -> isApplicable(lam.body(), available, caseOnBuiltinConstants);
            case Term.Apply apply -> isApplicable(
                    apply.function(), available, caseOnBuiltinConstants)
                    && isApplicable(apply.argument(), available, caseOnBuiltinConstants);
            case Term.Force force -> isApplicable(
                    force.term(), available, caseOnBuiltinConstants);
            case Term.Delay delay -> isApplicable(
                    delay.term(), available, caseOnBuiltinConstants);
            case Term.Const _, Term.Error _ -> true;
            case Term.Builtin builtin -> available.contains(builtin.fun());
            case Term.Constr constr -> constr.fields().stream()
                    .allMatch(field -> isApplicable(
                            field, available, caseOnBuiltinConstants));
            case Term.Case caseTerm -> (caseOnBuiltinConstants
                    || !(caseTerm.scrutinee() instanceof Term.Const))
                    && isApplicable(caseTerm.scrutinee(), available, caseOnBuiltinConstants)
                    && caseTerm.branches().stream()
                    .allMatch(branch -> isApplicable(
                            branch, available, caseOnBuiltinConstants));
        };
    }

    private Path selectExpectation(Path baseDir, Path overlayDir, String relativePath) {
        if (overlayDir != null) {
            var overlayFile = overlayDir.resolve(relativePath);
            if (Files.isRegularFile(overlayFile)) {
                return overlayFile;
            }
        }
        var baseFile = baseDir.resolve(relativePath);
        if (!Files.isRegularFile(baseFile)) {
            throw new AssertionError("Missing conformance expectation: " + baseFile);
        }
        return baseFile;
    }

    private void requireCount(long expected, long actual, String description) {
        if (actual != expected) {
            throw new AssertionError(description + ": expected " + expected + " but found " + actual);
        }
    }

    private record ConformanceProfile(
            String name,
            LedgerEvaluationTarget target,
            Path overlayDir,
            int expectedApplicableCases,
            int expectedNumericBudgets) {
    }

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
            case Constant.ArrayConst aa -> b instanceof Constant.ArrayConst ab
                    && aa.values().size() == ab.values().size()
                    && constantListsEqual(aa.values(), ab.values());
            case Constant.ValueConst va -> b instanceof Constant.ValueConst vb
                    && va.entries().size() == vb.entries().size()
                    && valueEntriesEqual(va.entries(), vb.entries());
        };
    }

    private boolean constantListsEqual(List<Constant> a, List<Constant> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!constantsEqual(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private boolean valueEntriesEqual(List<Constant.ValueConst.ValueEntry> a,
                                       List<Constant.ValueConst.ValueEntry> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i).policyId(), b.get(i).policyId())) return false;
            if (a.get(i).tokens().size() != b.get(i).tokens().size()) return false;
            for (int j = 0; j < a.get(i).tokens().size(); j++) {
                var ta = a.get(i).tokens().get(j);
                var tb = b.get(i).tokens().get(j);
                if (!Arrays.equals(ta.tokenName(), tb.tokenName())) return false;
                if (!ta.quantity().equals(tb.quantity())) return false;
            }
        }
        return true;
    }

    private boolean dataEqual(PlutusData a, PlutusData b) {
        return switch (a) {
            case PlutusData.IntData ia -> b instanceof PlutusData.IntData ib
                    && ia.value().equals(ib.value());
            case PlutusData.BytesData bsa -> b instanceof PlutusData.BytesData bsb
                    && Arrays.equals(bsa.value(), bsb.value());
            case PlutusData.ConstrData ca -> b instanceof PlutusData.ConstrData cb
                    && ca.constructorTag().equals(cb.constructorTag())
                    && ca.fields().size() == cb.fields().size()
                    && dataListsEqual(ca.fields(), cb.fields());
            case PlutusData.ListData la -> b instanceof PlutusData.ListData lb
                    && la.items().size() == lb.items().size()
                    && dataListsEqual(la.items(), lb.items());
            case PlutusData.MapData ma -> b instanceof PlutusData.MapData mb
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
