package org.julclang.vm.java;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.text.UplcParser;
import org.julclang.core.text.UplcPrinter;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.java.cost.CostModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.julclang.vm.java.EvalResultAssertions.assertSameResult;
import static org.junit.jupiter.api.Assertions.*;

class PreparedProgramTest {

    private static final LedgerEvaluationTarget V3 = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
    private final JavaVmProvider provider = new JavaVmProvider();

    @Test
    void preparedEvaluationOfEveryConformanceProgramMatchesEvaluation() throws Exception {
        int compared = 0;
        for (Path file : conformancePrograms()) {
            Program program;
            try {
                program = UplcParser.parseProgram(Files.readString(file));
            } catch (Exception parseError) {
                continue;
            }
            PreparedProgram prepared = provider.prepare(program, V3);
            for (ExBudget budget : new ExBudget[]{null, new ExBudget(30_000, 3_000)}) {
                var expected = provider.evaluate(program, V3, budget, EvalOptions.DEFAULT);
                assertSameResult(expected, prepared.evaluate(budget, EvalOptions.DEFAULT), file + " budget=" + budget);
                assertSameResult(expected, prepared.evaluate(budget, EvalOptions.DEFAULT),
                        file + " budget=" + budget + " (second run)");
            }
            compared++;
        }
        assertTrue(compared > 900, "compared " + compared + " conformance programs");
    }

    @Test
    void repeatedRunsAreIndependent() {
        // [(force (builtin trace)) (con string "hi") (con unit ())]
        var program = Program.plutusV3(Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.Trace)),
                Term.const_(Constant.string("hi"))), Term.const_(Constant.unit())));
        PreparedProgram prepared = provider.prepare(program, V3);

        var first = assertInstanceOf(EvalResult.Success.class, prepared.evaluate(null, EvalOptions.DEFAULT));
        var second = assertInstanceOf(EvalResult.Success.class, prepared.evaluate(null, EvalOptions.DEFAULT));

        assertEquals(List.of("hi"), first.traces());
        assertEquals(List.of("hi"), second.traces());
        assertEquals(first.consumed(), second.consumed());
    }

    @Test
    void concurrentRunsOfOnePreparedProgramGiveTheSameResult() throws Exception {
        List<Program> programs = new ArrayList<>();
        for (Path file : conformancePrograms()) {
            try {
                programs.add(UplcParser.parseProgram(Files.readString(file)));
            } catch (Exception parseError) {
                // not a program; the conformance test above covers the corpus
            }
            if (programs.size() == 200) {
                break;
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (Program program : programs) {
                PreparedProgram prepared = provider.prepare(program, V3);
                var expected = provider.evaluate(program, V3, null, EvalOptions.DEFAULT);
                List<Callable<EvalResult>> runs = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    runs.add(() -> prepared.evaluate(null, EvalOptions.DEFAULT));
                }
                for (Future<EvalResult> run : pool.invokeAll(runs)) {
                    assertSameResult(expected, run.get(), UplcPrinter.print(program.term()));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aProgramThatFailsValidationIsNotRun() {
        // dropList is a PlutusV3 builtin from protocol version 11 only
        var program = Program.plutusV3(Term.builtin(DefaultFun.DropList));
        var pv10 = LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3);
        var expected = assertInstanceOf(EvalResult.Failure.class,
                provider.evaluate(program, pv10, null, EvalOptions.DEFAULT));

        var actual = assertInstanceOf(EvalResult.Failure.class,
                provider.prepare(program, pv10).evaluate(null, EvalOptions.DEFAULT));

        assertEquals(expected.error(), actual.error());
        assertEquals(ExBudget.ZERO, actual.budgetConsumed());
    }

    @Test
    void theCostModelIsCapturedWhenTheProgramIsPrepared() {
        var program = Program.plutusV3(Term.apply(Term.apply(Term.builtin(DefaultFun.AddInteger),
                Term.const_(Constant.integer(1))), Term.const_(Constant.integer(2))));
        long[] defaults = CostModelParser.defaultToFlatArray(11);
        long[] doubled = Arrays.stream(defaults).map(v -> v * 2).toArray();
        provider.setCostModelParams(defaults, PlutusLanguage.PLUTUS_V3, 11, 0);
        var underDefaults = provider.evaluate(program, V3, null, EvalOptions.DEFAULT).budgetConsumed();
        PreparedProgram prepared = provider.prepare(program, V3);

        provider.setCostModelParams(doubled, PlutusLanguage.PLUTUS_V3, 11, 0);

        var underDoubled = provider.evaluate(program, V3, null, EvalOptions.DEFAULT).budgetConsumed();
        assertNotEquals(underDefaults, underDoubled);
        assertEquals(underDefaults, prepared.evaluate(null, EvalOptions.DEFAULT).budgetConsumed());
    }

    private List<Path> conformancePrograms() throws Exception {
        var dir = Paths.get(Objects.requireNonNull(getClass().getResource("/conformance")).toURI());
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".uplc")).sorted().toList();
        }
    }
}
