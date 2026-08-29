package com.bloxbean.julc.cli.cmd.verify;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;

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
