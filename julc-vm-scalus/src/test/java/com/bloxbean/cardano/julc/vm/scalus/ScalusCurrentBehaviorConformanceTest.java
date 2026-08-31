package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.NamedDeBruijn;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.text.UplcParser;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the behavior of the unconfigured, language-only V3 provider before ADR-033 changes it.
 * The current Scalus 1.1.0 default is PV11/E, so result and budget comparisons use the PV11
 * overlay. Structural exclusions are derived from parsed terms, never fixture paths.
 */
class ScalusCurrentBehaviorConformanceTest {

    private static final int EXPECTED_CASE_COUNT = 999;
    private static final int EXPECTED_PV10_APPLICABLE_CASES = 737;
    private static final int EXPECTED_BASE_NUMERIC_BUDGETS = 545;
    private static final int EXPECTED_PV11_NUMERIC_BUDGETS = 724;
    private static final int EXPECTED_PV11_OVERRIDES = 65;
    private static final int EXPECTED_PV11_BUDGET_OVERRIDES = 61;
    private static final int EXPECTED_PV11_RESULT_OVERRIDES = 4;
    private static final int EXPECTED_PARSE_ERRORS = 55;
    private static final int EXPECTED_BLS_VALIDATOR_REJECTIONS = 15;
    private static final int EXPECTED_NON_SERIALIZABLE_BLS_INPUTS = 111;
    private static final int EXPECTED_BLS_OUTPUT_ONLY_CASES = 12;
    private static final int EXPECTED_EVALUATED_CASES = 833;
    private static final int EXPECTED_EVALUATED_NUMERIC_BUDGETS = 617;
    private static final int EXPECTED_RESULT_CONVERSION_GAPS = 45;
    private static final int EXPECTED_HIGH_BYTE_DST_MISMATCHES = 3;
    private static final Set<String> EXPECTED_HIGH_BYTE_DST_MISMATCH_FIXTURES = Set.of(
            "builtin/semantics/bls12_381-cardano-crypto-tests/signature/large-dst/large-dst.uplc",
            "builtin/semantics/bls12_381_G1_hashToGroup/hash-dst-len-255/hash-dst-len-255.uplc",
            "builtin/semantics/bls12_381_G2_hashToGroup/hash-dst-len-255/hash-dst-len-255.uplc");

    private static final Pattern BUDGET_PATTERN =
            Pattern.compile("cpu:\\s*(\\d+)\\s*\\|\\s*mem:\\s*(\\d+)");

    @Test
    void recordsCurrentPv11DefaultCorpusBehavior() throws IOException, URISyntaxException {
        var conformanceDir = Paths.get(
                Objects.requireNonNull(getClass().getResource("/conformance")).toURI());
        var pv11OverlayDir = Paths.get(
                Objects.requireNonNull(getClass().getResource("/conformance-pv11")).toURI());
        var inputFiles = findInputFiles(conformanceDir);

        assertCorpusInventory(conformanceDir, pv11OverlayDir, inputFiles);

        var provider = new ScalusVmProvider();
        var counts = new LinkedHashMap<String, Integer>();
        var details = new ArrayList<String>();
        var resultMismatchFixtures = new java.util.LinkedHashSet<String>();

        for (var inputFile : inputFiles) {
            var relative = conformanceDir.relativize(inputFile);
            var input = Files.readString(inputFile).trim();
            var baseExpectedFile = selectExpectation(conformanceDir, null,
                    relative + ".expected");
            var pv11ExpectedFile = selectExpectation(conformanceDir, pv11OverlayDir,
                    relative + ".expected");
            var budgetFile = selectExpectation(conformanceDir, pv11OverlayDir,
                    relative + ".budget.expected");
            var baseExpected = Files.readString(baseExpectedFile).trim();
            var pv11Expected = Files.readString(pv11ExpectedFile).trim();

            var budgetMatcher = BUDGET_PATTERN.matcher(Files.readString(budgetFile));
            boolean hasNumericBudget = budgetMatcher.find();
            if (hasNumericBudget) {
                increment(counts, "PV11_NUMERIC_BUDGET");
            }

            Program program;
            try {
                program = UplcParser.parseProgram(input);
            } catch (Exception parseFailure) {
                if ("parse error".equals(pv11Expected)) {
                    increment(counts, "EXPECTED_PARSE_ERROR");
                    if (parseFailure.getMessage() != null
                            && parseFailure.getMessage().contains("Invalid bls12_381_")) {
                        increment(counts, "BLS_VALIDATOR_REJECTED_INVALID_LITERAL");
                    }
                } else {
                    increment(counts, "UNEXPECTED_PARSE_ERROR");
                    details.add(relative + " UNEXPECTED_PARSE_ERROR " + parseFailure.getMessage());
                }
                continue;
            }

            if ("parse error".equals(pv11Expected)) {
                increment(counts, "EXPECTED_PARSE_ERROR_BUT_PARSED");
                details.add(relative + " EXPECTED_PARSE_ERROR_BUT_PARSED");
                continue;
            }

            if (containsBlsLiteral(program.term())) {
                increment(counts, "NON_LEDGER_SERIALIZABLE_BLS_LITERAL");
                continue;
            }

            if (expectedOutputContainsBlsLiteral(pv11Expected)) {
                increment(counts, "BLS_OUTPUT_ONLY_APPLICABLE");
            }

            increment(counts, "EVALUATED_NON_BLS_INPUT");
            EvalResult result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
            boolean resultConversionGap = result instanceof EvalResult.Failure failure
                    && (failure.error().contains("Unsupported Scalus constant type")
                    || failure.error().contains("MlResult cannot be converted"));
            boolean numericBudgetExact = false;
            if (hasNumericBudget) {
                increment(counts, "PV11_NUMERIC_EVALUATED");
                long expectedCpu = Long.parseLong(budgetMatcher.group(1));
                long expectedMemory = Long.parseLong(budgetMatcher.group(2));
                if (result.budgetConsumed().cpuSteps() == expectedCpu
                        && result.budgetConsumed().memoryUnits() == expectedMemory) {
                    increment(counts, "PV11_NUMERIC_EXACT");
                    numericBudgetExact = true;
                } else {
                    increment(counts, "PV11_NUMERIC_MISMATCH");
                    details.add(relative + " PV11_NUMERIC_MISMATCH expected="
                            + expectedCpu + "/" + expectedMemory + " actual="
                            + result.budgetConsumed().cpuSteps() + "/"
                            + result.budgetConsumed().memoryUnits());
                }
                if (!resultConversionGap) {
                    increment(counts, "PV11_NUMERIC_BRIDGE_REPRESENTABLE");
                    if (result.budgetConsumed().cpuSteps() == expectedCpu
                            && result.budgetConsumed().memoryUnits() == expectedMemory) {
                        increment(counts, "PV11_NUMERIC_BRIDGE_EXACT");
                    } else {
                        increment(counts, "PV11_NUMERIC_BRIDGE_MISMATCH");
                    }
                }
            }

            if ("evaluation failure".equals(pv11Expected)) {
                if (result.isSuccess()) {
                    increment(counts, "EXPECTED_EVALUATION_FAILURE_BUT_SUCCEEDED");
                    details.add(relative + " EXPECTED_EVALUATION_FAILURE_BUT_SUCCEEDED");
                } else {
                    increment(counts, "EXPECTED_EVALUATION_FAILURE");
                    if (!baseExpected.equals(pv11Expected)) {
                        increment(counts, "PV11_RESULT_OVERRIDE_REQUIRED");
                    }
                }
                continue;
            }

            if (!result.isSuccess()) {
                var error = ((EvalResult.Failure) result).error();
                if (error.contains("Unsupported Scalus constant type")) {
                    increment(counts, "ARRAY_VALUE_RESULT_CONVERSION_GAP");
                } else if (error.contains("MlResult cannot be converted")) {
                    increment(counts, "BLS_MLRESULT_CONVERSION_GAP");
                    details.add(relative + " BLS_MLRESULT_CONVERSION_GAP " + error);
                } else {
                    increment(counts, "UNEXPECTED_EVALUATION_FAILURE");
                    details.add(relative + " UNEXPECTED_EVALUATION_FAILURE " + error);
                }
                continue;
            }

            var actual = ((EvalResult.Success) result).resultTerm();
            var pv11ExpectedTerm = UplcParser.parseProgram(pv11Expected).term();
            if (termsEqual(pv11ExpectedTerm, actual)) {
                increment(counts, "PV11_RESULT_MATCH");
                if (!baseExpected.equals(pv11Expected)) {
                    var baseExpectedTerm = UplcParser.parseProgram(baseExpected).term();
                    if (!termsEqual(baseExpectedTerm, actual)) {
                        increment(counts, "PV11_RESULT_OVERRIDE_REQUIRED");
                    }
                }
            } else {
                increment(counts, "SCALUS_HASHTOGROUP_DST_HIGH_BYTE");
                if (numericBudgetExact) {
                    increment(counts, "SCALUS_HASHTOGROUP_DST_HIGH_BYTE_BUDGET_EXACT");
                }
                resultMismatchFixtures.add(relative.toString().replace('\\', '/'));
                details.add(relative
                        + " SCALUS_HASHTOGROUP_DST_HIGH_BYTE budget exact, result differs");
            }
        }

        System.out.println("SCALUS_M1_COUNTS " + counts);
        details.forEach(detail -> System.out.println("SCALUS_M1_DETAIL " + detail));

        assertEquals(EXPECTED_PV11_NUMERIC_BUDGETS,
                counts.getOrDefault("PV11_NUMERIC_BUDGET", 0));
        assertEquals(EXPECTED_PARSE_ERRORS,
                counts.getOrDefault("EXPECTED_PARSE_ERROR", 0));
        assertEquals(EXPECTED_BLS_VALIDATOR_REJECTIONS,
                counts.getOrDefault("BLS_VALIDATOR_REJECTED_INVALID_LITERAL", 0));
        assertEquals(EXPECTED_NON_SERIALIZABLE_BLS_INPUTS,
                counts.getOrDefault("NON_LEDGER_SERIALIZABLE_BLS_LITERAL", 0));
        assertEquals(EXPECTED_BLS_OUTPUT_ONLY_CASES,
                counts.getOrDefault("BLS_OUTPUT_ONLY_APPLICABLE", 0));
        assertEquals(EXPECTED_EVALUATED_CASES,
                counts.getOrDefault("EVALUATED_NON_BLS_INPUT", 0));
        assertEquals(EXPECTED_EVALUATED_NUMERIC_BUDGETS,
                counts.getOrDefault("PV11_NUMERIC_EVALUATED", 0));
        assertEquals(EXPECTED_EVALUATED_NUMERIC_BUDGETS,
                counts.getOrDefault("PV11_NUMERIC_EXACT", 0));
        assertEquals(EXPECTED_RESULT_CONVERSION_GAPS,
                counts.getOrDefault("ARRAY_VALUE_RESULT_CONVERSION_GAP", 0));
        assertEquals(EXPECTED_HIGH_BYTE_DST_MISMATCHES,
                counts.getOrDefault("SCALUS_HASHTOGROUP_DST_HIGH_BYTE", 0));
        assertEquals(EXPECTED_HIGH_BYTE_DST_MISMATCHES,
                counts.getOrDefault("SCALUS_HASHTOGROUP_DST_HIGH_BYTE_BUDGET_EXACT", 0));
        assertEquals(EXPECTED_HIGH_BYTE_DST_MISMATCH_FIXTURES, resultMismatchFixtures);
        assertEquals(EXPECTED_CASE_COUNT,
                counts.getOrDefault("EXPECTED_PARSE_ERROR", 0)
                        + counts.getOrDefault("NON_LEDGER_SERIALIZABLE_BLS_LITERAL", 0)
                        + counts.getOrDefault("EVALUATED_NON_BLS_INPUT", 0));
        assertEquals(EXPECTED_EVALUATED_CASES,
                counts.getOrDefault("ARRAY_VALUE_RESULT_CONVERSION_GAP", 0)
                        + counts.getOrDefault("EXPECTED_EVALUATION_FAILURE", 0)
                        + counts.getOrDefault("PV11_RESULT_MATCH", 0)
                        + counts.getOrDefault("SCALUS_HASHTOGROUP_DST_HIGH_BYTE", 0));
        assertEquals(0, counts.getOrDefault("UNEXPECTED_PARSE_ERROR", 0));
        assertEquals(0, counts.getOrDefault("EXPECTED_PARSE_ERROR_BUT_PARSED", 0));
        assertEquals(0, counts.getOrDefault("PV11_NUMERIC_MISMATCH", 0));
        assertEquals(0, counts.getOrDefault("BLS_MLRESULT_CONVERSION_GAP", 0));
        assertEquals(0, counts.getOrDefault("UNEXPECTED_EVALUATION_FAILURE", 0));
        assertEquals(0, counts.getOrDefault("EXPECTED_EVALUATION_FAILURE_BUT_SUCCEEDED", 0));
    }

    private void assertCorpusInventory(Path conformanceDir, Path pv11OverlayDir,
                                       List<Path> inputFiles) throws IOException {
        assertEquals(EXPECTED_CASE_COUNT, inputFiles.size());

        var overlayFiles = new ArrayList<Path>();
        try (var walk = Files.walk(pv11OverlayDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("README.md"))
                    .sorted()
                    .forEach(overlayFiles::add);
        }
        assertEquals(EXPECTED_PV11_OVERRIDES, overlayFiles.size());
        assertEquals(EXPECTED_PV11_BUDGET_OVERRIDES,
                overlayFiles.stream().filter(path -> path.toString()
                        .endsWith(".budget.expected")).count());
        assertEquals(EXPECTED_PV11_RESULT_OVERRIDES,
                overlayFiles.stream().filter(path -> path.toString()
                        .endsWith(".uplc.expected")).count());
        for (var overlayFile : overlayFiles) {
            var baseFile = conformanceDir.resolve(pv11OverlayDir.relativize(overlayFile));
            assertTrue(Files.isRegularFile(baseFile),
                    "PV11 override has no base counterpart: " + overlayFile);
            assertTrue(!Files.readString(baseFile).trim()
                            .equals(Files.readString(overlayFile).trim()),
                    "PV11 override does not change its base file: " + overlayFile);
        }

        int baseNumericBudgets = 0;
        int pv10ApplicableCases = 0;
        for (var inputFile : inputFiles) {
            var relative = conformanceDir.relativize(inputFile);
            if (isPv10Applicable(inputFile)) {
                pv10ApplicableCases++;
                var budget = Files.readString(selectExpectation(
                        conformanceDir, null, relative + ".budget.expected"));
                if (BUDGET_PATTERN.matcher(budget).find()) {
                    baseNumericBudgets++;
                }
            }
        }
        assertEquals(EXPECTED_BASE_NUMERIC_BUDGETS, baseNumericBudgets);
        assertEquals(EXPECTED_PV10_APPLICABLE_CASES, pv10ApplicableCases);
    }

    private boolean isPv10Applicable(Path inputFile) throws IOException {
        try {
            var program = UplcParser.parseProgram(Files.readString(inputFile).trim());
            var profile = com.bloxbean.cardano.julc.vm.ProtocolFeatureRegistry.resolve(
                    com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget.pv10(
                            PlutusLanguage.PLUTUS_V3));
            return isApplicable(program.term(), profile.availableBuiltins(),
                    profile.caseOnBuiltinConstants());
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean isApplicable(Term term,
                                 java.util.Set<com.bloxbean.cardano.julc.core.DefaultFun> available,
                                 boolean caseOnBuiltinConstants) {
        return switch (term) {
            case Term.Var _ -> true;
            case Term.Lam lam -> isApplicable(lam.body(), available, caseOnBuiltinConstants);
            case Term.Apply apply -> isApplicable(apply.function(), available, caseOnBuiltinConstants)
                    && isApplicable(apply.argument(), available, caseOnBuiltinConstants);
            case Term.Force force -> isApplicable(force.term(), available, caseOnBuiltinConstants);
            case Term.Delay delay -> isApplicable(delay.term(), available, caseOnBuiltinConstants);
            case Term.Const _, Term.Error _ -> true;
            case Term.Builtin builtin -> available.contains(builtin.fun());
            case Term.Constr constr -> constr.fields().stream()
                    .allMatch(field -> isApplicable(field, available, caseOnBuiltinConstants));
            case Term.Case caseTerm -> (caseOnBuiltinConstants
                    || !(caseTerm.scrutinee() instanceof Term.Const))
                    && isApplicable(caseTerm.scrutinee(), available, caseOnBuiltinConstants)
                    && caseTerm.branches().stream()
                    .allMatch(branch -> isApplicable(branch, available, caseOnBuiltinConstants));
        };
    }

    private boolean expectedOutputContainsBlsLiteral(String expected) {
        if ("parse error".equals(expected) || "evaluation failure".equals(expected)) {
            return false;
        }
        try {
            return containsBlsLiteral(UplcParser.parseProgram(expected).term());
        } catch (Exception parseFailure) {
            throw new AssertionError(
                    "Expected successful output must be parseable for classification", parseFailure);
        }
    }

    private boolean containsBlsLiteral(Term term) {
        return switch (term) {
            case Term.Var _, Term.Error _, Term.Builtin _ -> false;
            case Term.Lam lam -> containsBlsLiteral(lam.body());
            case Term.Apply apply -> containsBlsLiteral(apply.function())
                    || containsBlsLiteral(apply.argument());
            case Term.Force force -> containsBlsLiteral(force.term());
            case Term.Delay delay -> containsBlsLiteral(delay.term());
            case Term.Const constant -> containsBlsLiteral(constant.value());
            case Term.Constr constr -> constr.fields().stream().anyMatch(this::containsBlsLiteral);
            case Term.Case caseTerm -> containsBlsLiteral(caseTerm.scrutinee())
                    || caseTerm.branches().stream().anyMatch(this::containsBlsLiteral);
        };
    }

    private boolean containsBlsLiteral(Constant constant) {
        return switch (constant) {
            case Constant.Bls12_381_G1Element _, Constant.Bls12_381_G2Element _,
                 Constant.Bls12_381_MlResult _ -> true;
            case Constant.ListConst list -> list.values().stream().anyMatch(this::containsBlsLiteral);
            case Constant.PairConst pair -> containsBlsLiteral(pair.first())
                    || containsBlsLiteral(pair.second());
            case Constant.ArrayConst array -> array.values().stream()
                    .anyMatch(this::containsBlsLiteral);
            default -> false;
        };
    }

    private List<Path> findInputFiles(Path conformanceDir) throws IOException {
        try (var walk = Files.walk(conformanceDir)) {
            return walk.filter(path -> path.toString().endsWith(".uplc"))
                    .filter(path -> !path.toString().endsWith(".expected"))
                    .sorted()
                    .toList();
        }
    }

    private Path selectExpectation(Path baseDir, Path overlayDir, String relativePath) {
        if (overlayDir != null) {
            var overlayFile = overlayDir.resolve(relativePath);
            if (Files.isRegularFile(overlayFile)) {
                return overlayFile;
            }
        }
        var baseFile = baseDir.resolve(relativePath);
        assertTrue(Files.isRegularFile(baseFile), "Missing expectation: " + baseFile);
        return baseFile;
    }

    private void increment(Map<String, Integer> counts, String reason) {
        counts.merge(reason, 1, Integer::sum);
    }

    private boolean termsEqual(Term a, Term b) {
        return switch (a) {
            case Term.Var va -> b instanceof Term.Var vb && variablesEqual(va.name(), vb.name());
            case Term.Lam la -> b instanceof Term.Lam lb && termsEqual(la.body(), lb.body());
            case Term.Apply aa -> b instanceof Term.Apply ab
                    && termsEqual(aa.function(), ab.function())
                    && termsEqual(aa.argument(), ab.argument());
            case Term.Force fa -> b instanceof Term.Force fb && termsEqual(fa.term(), fb.term());
            case Term.Delay da -> b instanceof Term.Delay db && termsEqual(da.term(), db.term());
            case Term.Const ca -> b instanceof Term.Const cb
                    && constantsEqual(ca.value(), cb.value());
            case Term.Builtin ba -> b instanceof Term.Builtin bb && ba.fun() == bb.fun();
            case Term.Error _ -> b instanceof Term.Error;
            case Term.Constr ca -> b instanceof Term.Constr cb
                    && ca.tag() == cb.tag() && termListsEqual(ca.fields(), cb.fields());
            case Term.Case ca -> b instanceof Term.Case cb
                    && termsEqual(ca.scrutinee(), cb.scrutinee())
                    && termListsEqual(ca.branches(), cb.branches());
        };
    }

    private boolean variablesEqual(NamedDeBruijn a, NamedDeBruijn b) {
        return a.index() == b.index();
    }

    private boolean termListsEqual(List<Term> a, List<Term> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!termsEqual(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private boolean constantsEqual(Constant a, Constant b) {
        return switch (a) {
            case Constant.IntegerConst value -> b instanceof Constant.IntegerConst other
                    && value.value().equals(other.value());
            case Constant.ByteStringConst value -> b instanceof Constant.ByteStringConst other
                    && Arrays.equals(value.value(), other.value());
            case Constant.StringConst value -> b instanceof Constant.StringConst other
                    && value.value().equals(other.value());
            case Constant.UnitConst _ -> b instanceof Constant.UnitConst;
            case Constant.BoolConst value -> b instanceof Constant.BoolConst other
                    && value.value() == other.value();
            case Constant.DataConst value -> b instanceof Constant.DataConst other
                    && dataEqual(value.value(), other.value());
            case Constant.ListConst value -> b instanceof Constant.ListConst other
                    && constantListsEqual(value.values(), other.values());
            case Constant.PairConst value -> b instanceof Constant.PairConst other
                    && constantsEqual(value.first(), other.first())
                    && constantsEqual(value.second(), other.second());
            case Constant.Bls12_381_G1Element value ->
                    b instanceof Constant.Bls12_381_G1Element other
                            && Arrays.equals(value.value(), other.value());
            case Constant.Bls12_381_G2Element value ->
                    b instanceof Constant.Bls12_381_G2Element other
                            && Arrays.equals(value.value(), other.value());
            case Constant.Bls12_381_MlResult value ->
                    b instanceof Constant.Bls12_381_MlResult other
                            && Arrays.equals(value.value(), other.value());
            case Constant.ArrayConst value -> b instanceof Constant.ArrayConst other
                    && constantListsEqual(value.values(), other.values());
            case Constant.ValueConst value -> b instanceof Constant.ValueConst other
                    && valueEntriesEqual(value.entries(), other.entries());
        };
    }

    private boolean constantListsEqual(List<Constant> a, List<Constant> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!constantsEqual(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private boolean dataEqual(PlutusData a, PlutusData b) {
        return switch (a) {
            case PlutusData.IntData value -> b instanceof PlutusData.IntData other
                    && value.value().equals(other.value());
            case PlutusData.BytesData value -> b instanceof PlutusData.BytesData other
                    && Arrays.equals(value.value(), other.value());
            case PlutusData.ConstrData value -> b instanceof PlutusData.ConstrData other
                    && value.constructorTag().equals(other.constructorTag())
                    && dataListsEqual(value.fields(), other.fields());
            case PlutusData.ListData value -> b instanceof PlutusData.ListData other
                    && dataListsEqual(value.items(), other.items());
            case PlutusData.MapData value -> b instanceof PlutusData.MapData other
                    && dataMapEntriesEqual(value.entries(), other.entries());
        };
    }

    private boolean dataListsEqual(List<PlutusData> a, List<PlutusData> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!dataEqual(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private boolean dataMapEntriesEqual(List<PlutusData.Pair> a, List<PlutusData.Pair> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!dataEqual(a.get(i).key(), b.get(i).key())
                    || !dataEqual(a.get(i).value(), b.get(i).value())) {
                return false;
            }
        }
        return true;
    }

    private boolean valueEntriesEqual(List<Constant.ValueConst.ValueEntry> a,
                                      List<Constant.ValueConst.ValueEntry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            var left = a.get(i);
            var right = b.get(i);
            if (!Arrays.equals(left.policyId(), right.policyId())
                    || left.tokens().size() != right.tokens().size()) {
                return false;
            }
            for (int j = 0; j < left.tokens().size(); j++) {
                var leftToken = left.tokens().get(j);
                var rightToken = right.tokens().get(j);
                if (!Arrays.equals(leftToken.tokenName(), rightToken.tokenName())
                        || !leftToken.quantity().equals(rightToken.quantity())) {
                    return false;
                }
            }
        }
        return true;
    }
}
