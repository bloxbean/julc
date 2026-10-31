package org.julclang.vm.corpus;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;

import java.util.List;

/**
 * Records what each {@code JulcVm} evaluation observed, for ADR-068 M0's UPLC-only semantic observations (D13):
 * the program's FLAT digest, the arguments, the outcome, the result's digest, the traces and the budget. Test-only,
 * as the compiler's corpus recorder: active only when the system property {@value #PROPERTY} names a directory,
 * when {@link #ACTIVE} is a {@code FileEvaluationRecorder} loaded by a class name read from a system property; when
 * it is unset {@link #ACTIVE} is {@code null} and each hook is one test of this {@code static final} field. A hook
 * runs after the evaluation and only reads its inputs and result.
 */
public interface EvaluationRecorder {

    /** The system property naming the corpus directory (the compiler's recorder uses the same one). */
    String PROPERTY = "julc.corpus.record";

    /** The recorder of this JVM, or {@code null} when {@value #PROPERTY} is unset. */
    EvaluationRecorder ACTIVE = Loader.load();

    /**
     * Records one evaluation.
     *
     * @param provider the VM provider's name
     * @param target   the language or ledger target the evaluation used
     * @param program  the evaluated program
     * @param args     the applied arguments, or an empty list
     * @param budget   the budget limit, or null for none
     * @param result   what the evaluation produced
     */
    void record(String provider, String target, Program program, List<PlutusData> args, ExBudget budget,
                EvalResult result);

    /** Loads the recorder class named by {@value #PROPERTY}{@code .evaluations}, by default the file recorder. */
    final class Loader {
        private Loader() {
        }

        static EvaluationRecorder load() {
            String dir = System.getProperty(PROPERTY);
            if (dir == null || dir.isBlank()) return null;
            String implementation = System.getProperty(PROPERTY + ".evaluations",
                    "org.julclang.vm.corpus.FileEvaluationRecorder");
            try {
                return (EvaluationRecorder) Class.forName(implementation).getConstructor(String.class).newInstance(dir);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("evaluation recorder " + implementation + " for " + dir, e);
            }
        }
    }
}
