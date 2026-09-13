package org.julclang.cli.cmd.verify;

import org.julclang.compiler.CompileResult;
import org.julclang.core.PlutusData;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;

import java.util.List;

/** Protocol-aware evaluator for committed compiler-output fixtures. */
final class VerificationExecution {

    private VerificationExecution() {
    }

    static EvalResult evaluate(CompileResult compiled, PlutusData... arguments) {
        return JulcVm.create().evaluateWithArgs(
                compiled.program(), compiled.target().ledgerTarget(),
                List.of(arguments), null, EvalOptions.DEFAULT);
    }
}
