package com.bloxbean.cardano.julc.benchmark.optimization;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.vm.*;
import com.bloxbean.cardano.julc.vm.java.JavaVmProvider;
import com.bloxbean.cardano.julc.vm.truffle.TruffleVmProvider;
import com.bloxbean.cardano.julc.vm.scalus.ScalusVmProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Term-level vectors: independent of compiler lowering and the builtin conformance inventory. */
class ListCaseSemanticsTest {
    @ParameterizedTest
    @ValueSource(strings = {"java", "truffle", "scalus"})
    void pinsBranchOrderHeadTailBindingAndLaziness(String backend) {
        var empty = list();
        var singleton = list(17);
        var many = list(17, 23, 42);
        // Error in the unselected branch must never be evaluated.
        success(backend, Term.case_(empty, new Term.Error(), integer(9)), integer(9));
        for (Term xs : List.of(singleton, many)) {
            success(backend, Term.case_(xs, Term.lam("h", Term.lam("t", Term.var(2))),
                    new Term.Error()), integer(17));
        }
        success(backend, Term.case_(singleton, Term.lam("h", Term.lam("t", Term.var(1))),
                new Term.Error()), empty);
        success(backend, Term.case_(many, Term.lam("h", Term.lam("t", Term.var(1))),
                new Term.Error()), list(23, 42));
        // Both selected failure paths must still fail.
        assertFalse(evaluate(backend, Term.case_(empty, integer(9), new Term.Error())).isSuccess());
        assertFalse(evaluate(backend, Term.case_(many,
                Term.lam("h", Term.lam("t", new Term.Error())), integer(9))).isSuccess());
    }

    private static void success(String backend, Term term, Term expected) {
        var result = assertInstanceOf(EvalResult.Success.class, evaluate(backend, term), backend);
        assertEquals(expected, result.resultTerm(), backend);
        assertEquals(List.of(), result.traces(), backend);
    }

    private static EvalResult evaluate(String backend, Term term) {
        JulcVmProvider provider = switch (backend) {
            case "java" -> new JavaVmProvider();
            case "truffle" -> new TruffleVmProvider();
            case "scalus" -> new ScalusVmProvider();
            default -> throw new IllegalArgumentException(backend);
        };
        var program = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(Program.plutusV3(term)));
        // Scalus is a language-level cross-check, not a certified ledger-target backend.
        return backend.equals("scalus")
                ? provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null)
                : provider.evaluate(program, LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), null);
    }

    private static Term integer(long value) { return Term.const_(Constant.integer(value)); }

    private static Term list(long... values) {
        return Term.const_(new Constant.ListConst(DefaultUni.INTEGER,
                java.util.Arrays.stream(values).mapToObj(Constant::integer).toList()));
    }
}
