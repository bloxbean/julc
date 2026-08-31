package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.NamedDeBruijn;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.text.UplcParser;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
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

/** ADR-033 target-aware Scalus conformance and bundled-default characterization matrix. */
class ScalusTargetAwareConformanceTest {

    private static final int EXPECTED_CASE_COUNT = 999;
    private static final int EXPECTED_PV10_APPLICABLE_CASES = 737;
    private static final int EXPECTED_BASE_NUMERIC_BUDGETS = 545;
    private static final int EXPECTED_PV11_NUMERIC_BUDGETS = 724;
    private static final int EXPECTED_PV11_OVERRIDES = 65;
    private static final int EXPECTED_PV11_BUDGET_OVERRIDES = 61;
    private static final int EXPECTED_PV11_RESULT_OVERRIDES = 4;
    private static final int EXPECTED_PARSE_ERRORS = 55;
    private static final int EXPECTED_NON_SERIALIZABLE_BLS_INPUTS = 111;
    private static final int EXPECTED_HIGH_BYTE_DST_MISMATCHES = 3;
    private static final Set<String> EXPECTED_HIGH_BYTE_DST_MISMATCH_FIXTURES = Set.of(
            "builtin/semantics/bls12_381-cardano-crypto-tests/signature/large-dst/large-dst.uplc",
            "builtin/semantics/bls12_381_G1_hashToGroup/hash-dst-len-255/hash-dst-len-255.uplc",
            "builtin/semantics/bls12_381_G2_hashToGroup/hash-dst-len-255/hash-dst-len-255.uplc");

    private static final Pattern BUDGET_PATTERN =
            Pattern.compile("cpu:\\s*(\\d+)\\s*\\|\\s*mem:\\s*(\\d+)");

    @Test
    void runsSuppliedAndBundledTargetAwareMatrices()
            throws IOException, URISyntaxException {
        var conformanceDir = Paths.get(
                Objects.requireNonNull(getClass().getResource("/conformance")).toURI());
        var pv11OverlayDir = Paths.get(
                Objects.requireNonNull(getClass().getResource("/conformance-pv11")).toURI());
        var inputFiles = findInputFiles(conformanceDir);

        assertCorpusInventory(conformanceDir, pv11OverlayDir, inputFiles);

        var profiles = List.of(
                new ConformanceProfile(
                        "V3/PV10/C",
                        LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3),
                        null,
                        "v3-pv10-C-f92b7d7d8.txt",
                        EXPECTED_PV10_APPLICABLE_CASES,
                        EXPECTED_BASE_NUMERIC_BUDGETS,
                        63),
                new ConformanceProfile(
                        "V3/PV11/E",
                        LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                        pv11OverlayDir,
                        "v3-pv11-E-f92b7d7d8.txt",
                        EXPECTED_CASE_COUNT,
                        EXPECTED_PV11_NUMERIC_BUDGETS,
                        EXPECTED_NON_SERIALIZABLE_BLS_INPUTS));

        for (var profile : profiles) {
            for (var costSource : CostSource.values()) {
                runMatrix(conformanceDir, inputFiles, profile, costSource);
            }
        }
    }

    private void runMatrix(
            Path conformanceDir, List<Path> inputFiles,
            ConformanceProfile profile, CostSource costSource) throws IOException {
        var provider = new ScalusVmProvider();
        var costModelParams = costSource == CostSource.SUPPLIED_PROFILE
                ? readPinnedParams(profile.resource())
                : readBundledParams(profile);
        if (costSource == CostSource.BUNDLED_DEFAULT
                && profile.target().protocolVersion().major() == 10) {
            reportBundledParameterDifferences(
                    readPinnedParams(profile.resource()), costModelParams);
        }
        provider.setCostModelParams(costModelParams, profile.target());

        var counts = new LinkedHashMap<String, Integer>();
        var details = new ArrayList<String>();
        var dstMismatchFixtures = new java.util.LinkedHashSet<String>();

        for (var inputFile : inputFiles) {
            var relative = conformanceDir.relativize(inputFile);
            var fixture = relative.toString().replace('\\', '/');
            var inapplicableReason = inapplicableReason(inputFile, profile.target());
            if (inapplicableReason != null) {
                increment(counts, "INAPPLICABLE");
                increment(counts, inapplicableReason);
                continue;
            }
            increment(counts, "APPLICABLE");

            var expectedFile = selectExpectation(
                    conformanceDir, profile.overlayDir(), relative + ".expected");
            var budgetFile = selectExpectation(
                    conformanceDir, profile.overlayDir(), relative + ".budget.expected");
            var expected = Files.readString(expectedFile).trim();
            var budgetMatcher = BUDGET_PATTERN.matcher(Files.readString(budgetFile));
            boolean hasNumericBudget = budgetMatcher.find();
            if (hasNumericBudget) increment(counts, "NUMERIC_BUDGET");

            Program program;
            try {
                program = UplcParser.parseProgram(Files.readString(inputFile).trim());
            } catch (Exception parseFailure) {
                if ("parse error".equals(expected)) {
                    increment(counts, "PARSE_ERROR");
                } else {
                    increment(counts, "RESULT_MISMATCH");
                    details.add(fixture + " unexpected parse error: "
                            + parseFailure.getMessage());
                }
                continue;
            }

            if ("parse error".equals(expected)) {
                increment(counts, "RESULT_MISMATCH");
                details.add(fixture + " expected parse error but parsed");
                continue;
            }
            if (containsBlsLiteral(program.term())) {
                increment(counts, "NON_LEDGER_SERIALIZABLE_BLS_LITERAL");
                continue;
            }

            EvalResult result = provider.evaluateCandidate(
                    program, profile.target(), null, null);

            boolean numericBudgetExact = false;
            if (hasNumericBudget) {
                increment(counts, "NUMERIC_EVALUATED");
                long expectedCpu = Long.parseLong(budgetMatcher.group(1));
                long expectedMemory = Long.parseLong(budgetMatcher.group(2));
                if (result.budgetConsumed().cpuSteps() == expectedCpu
                        && result.budgetConsumed().memoryUnits() == expectedMemory) {
                    increment(counts, "NUMERIC_EXACT");
                    numericBudgetExact = true;
                } else {
                    increment(counts, "NUMERIC_MISMATCH");
                    details.add(fixture + " budget expected=" + expectedCpu + "/"
                            + expectedMemory + " actual="
                            + result.budgetConsumed().cpuSteps() + "/"
                            + result.budgetConsumed().memoryUnits());
                }
            }

            if ("evaluation failure".equals(expected)) {
                if (result.isSuccess()) {
                    increment(counts, "RESULT_MISMATCH");
                    details.add(fixture + " expected failure but succeeded");
                } else {
                    increment(counts, "EXPECTED_EVALUATION_FAILURE");
                }
                continue;
            }

            if (!result.isSuccess()) {
                var error = ((EvalResult.Failure) result).error();
                if (error.contains("MlResult cannot be converted")) {
                    increment(counts, "NON_SERIALIZABLE_MLRESULT_OUTPUT");
                } else {
                    increment(counts, "RESULT_MISMATCH");
                    details.add(fixture + " unexpected evaluation failure: " + error);
                }
                continue;
            }

            var expectedTerm = UplcParser.parseProgram(expected).term();
            var actualTerm = ((EvalResult.Success) result).resultTerm();
            if (termsEqual(expectedTerm, actualTerm)) {
                increment(counts, "RESULT_MATCH");
            } else if (EXPECTED_HIGH_BYTE_DST_MISMATCH_FIXTURES.contains(fixture)) {
                increment(counts, "SCALUS_HASHTOGROUP_DST_HIGH_BYTE");
                if (numericBudgetExact) {
                    increment(counts, "SCALUS_HASHTOGROUP_DST_HIGH_BYTE_BUDGET_EXACT");
                }
                dstMismatchFixtures.add(fixture);
            } else {
                increment(counts, "RESULT_MISMATCH");
                details.add(fixture + " structural result mismatch");
            }
        }

        System.out.println("SCALUS_M6_COUNTS " + profile.name() + " "
                + costSource + " " + counts);
        details.forEach(detail -> System.out.println("SCALUS_M6_DETAIL "
                + profile.name() + " " + costSource + " " + detail));

        var expectedCounts = expectedCounts(profile, costSource);
        assertEquals(profile.expectedApplicable(), counts.getOrDefault("APPLICABLE", 0));
        assertEquals(EXPECTED_CASE_COUNT - profile.expectedApplicable(),
                counts.getOrDefault("INAPPLICABLE", 0));
        assertEquals(profile.target().protocolVersion().major() == 10 ? 236 : 0,
                counts.getOrDefault("BATCH6_BUILTIN_UNAVAILABLE", 0));
        assertEquals(profile.target().protocolVersion().major() == 10 ? 26 : 0,
                counts.getOrDefault("CASE_ON_BUILTIN_CONSTANT_UNAVAILABLE", 0));
        assertEquals(profile.expectedNumericBudgets(),
                counts.getOrDefault("NUMERIC_BUDGET", 0));
        assertEquals(EXPECTED_PARSE_ERRORS, counts.getOrDefault("PARSE_ERROR", 0));
        assertEquals(profile.expectedBlsLiterals(),
                counts.getOrDefault("NON_LEDGER_SERIALIZABLE_BLS_LITERAL", 0));
        assertEquals(EXPECTED_HIGH_BYTE_DST_MISMATCHES,
                counts.getOrDefault("SCALUS_HASHTOGROUP_DST_HIGH_BYTE", 0));
        assertEquals(EXPECTED_HIGH_BYTE_DST_MISMATCHES,
                counts.getOrDefault("SCALUS_HASHTOGROUP_DST_HIGH_BYTE_BUDGET_EXACT", 0));
        assertEquals(EXPECTED_HIGH_BYTE_DST_MISMATCH_FIXTURES, dstMismatchFixtures);
        assertEquals(0, counts.getOrDefault("NON_SERIALIZABLE_MLRESULT_OUTPUT", 0));
        assertEquals(expectedCounts.numericEvaluated(),
                counts.getOrDefault("NUMERIC_EVALUATED", 0));
        assertEquals(expectedCounts.numericExact(),
                counts.getOrDefault("NUMERIC_EXACT", 0));
        assertEquals(expectedCounts.numericMismatch(),
                counts.getOrDefault("NUMERIC_MISMATCH", 0));
        assertEquals(expectedCounts.resultMatches(),
                counts.getOrDefault("RESULT_MATCH", 0));
        assertEquals(expectedCounts.expectedFailures(),
                counts.getOrDefault("EXPECTED_EVALUATION_FAILURE", 0));
        assertEquals(expectedCounts.resultMismatches(),
                counts.getOrDefault("RESULT_MISMATCH", 0));
        assertEquals(counts.getOrDefault("NUMERIC_EVALUATED", 0),
                counts.getOrDefault("NUMERIC_EXACT", 0)
                        + counts.getOrDefault("NUMERIC_MISMATCH", 0));
        assertEquals(profile.expectedApplicable(),
                counts.getOrDefault("PARSE_ERROR", 0)
                        + counts.getOrDefault("NON_LEDGER_SERIALIZABLE_BLS_LITERAL", 0)
                        + counts.getOrDefault("NON_SERIALIZABLE_MLRESULT_OUTPUT", 0)
                        + counts.getOrDefault("EXPECTED_EVALUATION_FAILURE", 0)
                        + counts.getOrDefault("RESULT_MATCH", 0)
                        + counts.getOrDefault("SCALUS_HASHTOGROUP_DST_HIGH_BYTE", 0)
                        + counts.getOrDefault("RESULT_MISMATCH", 0));
        if (costSource == CostSource.SUPPLIED_PROFILE) {
            assertEquals(0, counts.getOrDefault("NUMERIC_MISMATCH", 0),
                    () -> String.join("\n", details));
            assertEquals(0, counts.getOrDefault("RESULT_MISMATCH", 0),
                    () -> String.join("\n", details));
        }
    }

    private ExpectedCounts expectedCounts(
            ConformanceProfile profile, CostSource costSource) {
        if (profile.target().protocolVersion().major() == 10) {
            return costSource == CostSource.SUPPLIED_PROFILE
                    ? new ExpectedCounts(482, 482, 0, 479, 137, 0)
                    : new ExpectedCounts(482, 445, 37, 479, 137, 0);
        }
        return new ExpectedCounts(617, 617, 0, 614, 216, 0);
    }

    private long[] readPinnedParams(String resource) throws IOException {
        var stream = Objects.requireNonNull(
                getClass().getResourceAsStream("/costmodels/" + resource), resource);
        try (var reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .mapToLong(Long::parseLong)
                    .toArray();
        }
    }

    private long[] readBundledParams(ConformanceProfile profile) {
        var models = scalus.cardano.ledger.CardanoInfo.mainnet()
                .protocolParams().costModels().models();
        @SuppressWarnings("unchecked")
        var values = (scala.collection.immutable.IndexedSeq<Object>) models.apply(
                Integer.valueOf(scalus.cardano.ledger.Language.PlutusV3.languageId()));
        var result = new long[profile.expectedNumericParams()];
        for (int i = 0; i < result.length; i++) {
            result[i] = ((Number) values.apply(i)).longValue();
        }
        return result;
    }

    private void reportBundledParameterDifferences(long[] pinned, long[] bundled) {
        var actual = new ArrayList<BundledParamDiff>();
        for (int i = 0; i < pinned.length; i++) {
            if (pinned[i] != bundled[i]) {
                actual.add(new BundledParamDiff(i, paramName(i), pinned[i], bundled[i]));
                System.out.println("SCALUS_M6_BUNDLED_PARAM_DIFF index=" + i
                        + " name=" + paramName(i) + " pinned=" + pinned[i]
                        + " bundled=" + bundled[i]);
            }
        }
        assertEquals(List.of(
                new BundledParamDiff(54,
                        "divideInteger-cpu-arguments-model-arguments-c11", 549, 960),
                new BundledParamDiff(64,
                        "equalsByteString-cpu-arguments-constant", 24548, 30623),
                new BundledParamDiff(65,
                        "equalsByteString-cpu-arguments-intercept", 29498, 28755),
                new BundledParamDiff(66,
                        "equalsByteString-cpu-arguments-slope", 38, 75),
                new BundledParamDiff(119,
                        "modInteger-cpu-arguments-model-arguments-c11", 549, 960),
                new BundledParamDiff(135,
                        "quotientInteger-cpu-arguments-model-arguments-c11", 549, 960),
                new BundledParamDiff(146,
                        "remainderInteger-cpu-arguments-model-arguments-c11", 549, 960)),
                actual);
    }

    private String paramName(int index) {
        return switch (index) {
            case 54 -> "divideInteger-cpu-arguments-model-arguments-c11";
            case 64 -> "equalsByteString-cpu-arguments-constant";
            case 65 -> "equalsByteString-cpu-arguments-intercept";
            case 66 -> "equalsByteString-cpu-arguments-slope";
            case 119 -> "modInteger-cpu-arguments-model-arguments-c11";
            case 135 -> "quotientInteger-cpu-arguments-model-arguments-c11";
            case 146 -> "remainderInteger-cpu-arguments-model-arguments-c11";
            default -> "unexpected-index-" + index;
        };
    }

    private boolean isApplicable(Path inputFile, LedgerEvaluationTarget target)
            throws IOException {
        return inapplicableReason(inputFile, target) == null;
    }

    private String inapplicableReason(Path inputFile, LedgerEvaluationTarget target)
            throws IOException {
        try {
            var program = UplcParser.parseProgram(Files.readString(inputFile).trim());
            var profile = com.bloxbean.cardano.julc.vm.ProtocolFeatureRegistry.resolve(target);
            if (containsUnavailableBuiltin(program.term(), profile.availableBuiltins())) {
                return "BATCH6_BUILTIN_UNAVAILABLE";
            }
            if (!profile.caseOnBuiltinConstants()
                    && containsCaseOnBuiltinConstant(program.term())) {
                return "CASE_ON_BUILTIN_CONSTANT_UNAVAILABLE";
            }
            return null;
        } catch (Exception ignored) {
            // Parse-error fixtures are applicable just as in the Java runner;
            // their expected parse failure is classified later in runMatrix.
            return null;
        }
    }

    private boolean containsUnavailableBuiltin(
            Term term, java.util.Set<com.bloxbean.cardano.julc.core.DefaultFun> available) {
        return switch (term) {
            case Term.Var _, Term.Const _, Term.Error _ -> false;
            case Term.Lam lam -> containsUnavailableBuiltin(lam.body(), available);
            case Term.Apply apply -> containsUnavailableBuiltin(apply.function(), available)
                    || containsUnavailableBuiltin(apply.argument(), available);
            case Term.Force force -> containsUnavailableBuiltin(force.term(), available);
            case Term.Delay delay -> containsUnavailableBuiltin(delay.term(), available);
            case Term.Builtin builtin -> !available.contains(builtin.fun());
            case Term.Constr constr -> constr.fields().stream()
                    .anyMatch(field -> containsUnavailableBuiltin(field, available));
            case Term.Case caseTerm -> containsUnavailableBuiltin(
                    caseTerm.scrutinee(), available)
                    || caseTerm.branches().stream()
                    .anyMatch(branch -> containsUnavailableBuiltin(branch, available));
        };
    }

    private boolean containsCaseOnBuiltinConstant(Term term) {
        return switch (term) {
            case Term.Var _, Term.Const _, Term.Error _, Term.Builtin _ -> false;
            case Term.Lam lam -> containsCaseOnBuiltinConstant(lam.body());
            case Term.Apply apply -> containsCaseOnBuiltinConstant(apply.function())
                    || containsCaseOnBuiltinConstant(apply.argument());
            case Term.Force force -> containsCaseOnBuiltinConstant(force.term());
            case Term.Delay delay -> containsCaseOnBuiltinConstant(delay.term());
            case Term.Constr constr -> constr.fields().stream()
                    .anyMatch(this::containsCaseOnBuiltinConstant);
            case Term.Case caseTerm -> caseTerm.scrutinee() instanceof Term.Const
                    || containsCaseOnBuiltinConstant(caseTerm.scrutinee())
                    || caseTerm.branches().stream()
                    .anyMatch(this::containsCaseOnBuiltinConstant);
        };
    }

    private enum CostSource {
        SUPPLIED_PROFILE,
        BUNDLED_DEFAULT
    }

    private record ConformanceProfile(
            String name,
            LedgerEvaluationTarget target,
            Path overlayDir,
            String resource,
            int expectedApplicable,
            int expectedNumericBudgets,
            int expectedBlsLiterals) {
        int expectedNumericParams() {
            return target.protocolVersion().major() == 10 ? 297 : 350;
        }
    }

    private record ExpectedCounts(
            int numericEvaluated,
            int numericExact,
            int numericMismatch,
            int resultMatches,
            int expectedFailures,
            int resultMismatches) {}

    private record BundledParamDiff(
            int index, String name, long pinned, long bundled) {}

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
            if (isApplicable(inputFile,
                    LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3))) {
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
