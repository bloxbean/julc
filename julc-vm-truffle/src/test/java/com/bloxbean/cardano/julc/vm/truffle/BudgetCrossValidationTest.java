package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.text.UplcParser;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.JavaVmProvider;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies EXACT budget parity between the Truffle and Java backends.
 * <p>
 * Both cpuSteps and memoryUnits must be identical — zero tolerance.
 * This is guaranteed by sharing the same CostTracker, MachineCosts,
 * BuiltinCostModel, and DefaultCostModel implementations.
 */
class BudgetCrossValidationTest {

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

    // --- Conformance-driven budget validation ---

    @TestFactory
    Stream<DynamicTest> budgetCrossValidation() throws IOException, URISyntaxException {
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
                                () -> runBudgetValidation(uplcFile, expectedFile)));
                    });
        }
        return testCases.stream();
    }

    private void runBudgetValidation(Path uplcFile, Path expectedFile) throws IOException {
        String input = Files.readString(uplcFile).trim();
        String expected = Files.readString(expectedFile).trim();

        if (expected.contains("con value") || expected.contains("con array")) return;

        Program program;
        try {
            program = UplcParser.parseProgram(input);
        } catch (Exception e) {
            return;
        }
        if ("parse error".equals(expected)) return;

        PlutusLanguage language = PlutusLanguage.PLUTUS_V3;

        EvalResult truffleResult = TRUFFLE.evaluate(program, language, null);
        EvalResult javaResult = JAVA.evaluate(program, language, null);

        // Both backends must agree on success/failure
        assertEquals(javaResult.isSuccess(), truffleResult.isSuccess(),
                "Outcome mismatch for " + uplcFile.getFileName() +
                "\nJava:    " + (javaResult.isSuccess() ? "Success" : "Failure") +
                "\nTruffle: " + (truffleResult.isSuccess() ? "Success" : "Failure"));

        ExBudget truffleBudget = truffleResult.budgetConsumed();
        ExBudget javaBudget = javaResult.budgetConsumed();

        assertEquals(javaBudget.cpuSteps(), truffleBudget.cpuSteps(),
                "CPU mismatch for " + uplcFile.getFileName() +
                "\nJava:    " + javaBudget +
                "\nTruffle: " + truffleBudget);

        assertEquals(javaBudget.memoryUnits(), truffleBudget.memoryUnits(),
                "Memory mismatch for " + uplcFile.getFileName() +
                "\nJava:    " + javaBudget +
                "\nTruffle: " + truffleBudget);
    }

    // --- Representative program budget tests ---

    @Test
    void factorialBudgetParity() {
        // fact n = if n <= 1 then 1 else n * fact(n-1)
        // Encode as UPLC: (\f -> f f 10) (\self n -> if n <= 1 then 1 else n * self self (n-1))
        var program = buildFactorialProgram(10);
        assertExactBudgetMatch(program, "factorial(10)");
    }

    @Test
    void addIntegerBudgetParity() {
        var add = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger),
                        Term.const_(Constant.integer(BigInteger.valueOf(1000000)))),
                Term.const_(Constant.integer(BigInteger.valueOf(2000000))));
        var program = new Program(1, 0, 0, add);
        assertExactBudgetMatch(program, "addInteger(1M, 2M)");
    }

    @Test
    void nestedLambdaBudgetParity() {
        // (\x -> \y -> x + y) 3 4
        var body = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger),
                        Term.var(new NamedDeBruijn("x", 2))),
                Term.var(new NamedDeBruijn("y", 1)));
        var innerLam = Term.lam("y", body);
        var outerLam = Term.lam("x", innerLam);
        var app = Term.apply(
                Term.apply(outerLam, Term.const_(Constant.integer(BigInteger.valueOf(3)))),
                Term.const_(Constant.integer(BigInteger.valueOf(4))));
        var program = new Program(1, 0, 0, app);
        assertExactBudgetMatch(program, "nestedLambda");
    }

    @Test
    void ifThenElseBudgetParity() {
        var ite = Term.force(Term.apply(
                Term.apply(
                        Term.apply(
                                Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                Term.const_(Constant.bool(false))),
                        Term.delay(Term.const_(Constant.integer(BigInteger.ONE)))),
                Term.delay(Term.const_(Constant.integer(BigInteger.TWO)))));
        var program = new Program(1, 0, 0, ite);
        assertExactBudgetMatch(program, "ifThenElse(false)");
    }

    @Test
    void traceBudgetParity() {
        var traced = Term.apply(
                Term.apply(
                        Term.force(Term.builtin(DefaultFun.Trace)),
                        Term.const_(Constant.string("debug"))),
                Term.const_(Constant.integer(BigInteger.valueOf(42))));
        var program = new Program(1, 0, 0, traced);
        assertExactBudgetMatch(program, "trace");
    }

    @Test
    void constrCaseBudgetParity() {
        var scrutinee = Term.constr(0,
                Term.const_(Constant.integer(BigInteger.ONE)));
        var branch0 = Term.lam("x", Term.var(new NamedDeBruijn("x", 1)));
        var branch1 = Term.lam("x", Term.const_(Constant.integer(BigInteger.ZERO)));
        var caseExpr = Term.case_(scrutinee, branch0, branch1);
        var program = new Program(1, 1, 0, caseExpr);
        assertExactBudgetMatch(program, "constr+case");
    }

    private void assertExactBudgetMatch(Program program, String label) {
        EvalResult truffleResult = TRUFFLE.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        EvalResult javaResult = JAVA.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertEquals(javaResult.isSuccess(), truffleResult.isSuccess(),
                label + ": outcome mismatch");

        ExBudget javaBudget = javaResult.budgetConsumed();
        ExBudget truffleBudget = truffleResult.budgetConsumed();

        assertEquals(javaBudget.cpuSteps(), truffleBudget.cpuSteps(),
                label + " CPU — Java: " + javaBudget.cpuSteps() +
                ", Truffle: " + truffleBudget.cpuSteps());

        assertEquals(javaBudget.memoryUnits(), truffleBudget.memoryUnits(),
                label + " Memory — Java: " + javaBudget.memoryUnits() +
                ", Truffle: " + truffleBudget.memoryUnits());
    }

    /**
     * Build a factorial program: (\f -> f f 10) (\self n -> if n <= 1 then 1 else n * self self (n-1))
     */
    private Program buildFactorialProgram(int n) {
        // Body: if (n <= 1) then 1 else n * (self self (n - 1))
        var nVar = Term.var(new NamedDeBruijn("n", 1));
        var selfVar = Term.var(new NamedDeBruijn("self", 2));

        // n <= 1
        var cond = Term.apply(
                Term.apply(Term.builtin(DefaultFun.LessThanEqualsInteger), nVar),
                Term.const_(Constant.integer(BigInteger.ONE)));

        // n - 1
        var nMinus1 = Term.apply(
                Term.apply(Term.builtin(DefaultFun.SubtractInteger), nVar),
                Term.const_(Constant.integer(BigInteger.ONE)));

        // self self (n-1)
        var recurse = Term.apply(Term.apply(selfVar, selfVar), nMinus1);

        // n * recurse
        var mul = Term.apply(
                Term.apply(Term.builtin(DefaultFun.MultiplyInteger), nVar),
                recurse);

        // if cond then 1 else mul
        var ite = Term.force(Term.apply(
                Term.apply(
                        Term.apply(
                                Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                cond),
                        Term.delay(Term.const_(Constant.integer(BigInteger.ONE)))),
                Term.delay(mul)));

        var factBody = Term.lam("self", Term.lam("n", ite));
        var app = Term.apply(
                Term.apply(factBody, factBody),
                Term.const_(Constant.integer(BigInteger.valueOf(n))));

        return new Program(1, 0, 0, app);
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
}
