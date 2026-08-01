package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;

import java.util.ArrayDeque;

/** Protocol-aware validation performed before CEK execution. */
public final class ProgramValidator {

    private ProgramValidator() {
    }

    public static void validate(Program program, ProtocolFeatureProfile profile) {
        profile.validateProgramVersion(program);
        var programVersion = UplcVersion.from(program);
        var pending = new ArrayDeque<Term>();
        pending.push(program.term());

        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case Term.Var _, Term.Const _, Term.Error _ -> {
                }
                case Term.Builtin builtin -> {
                    if (!profile.isBuiltinAvailable(builtin.fun())) {
                        throw new UnsupportedLedgerTargetException(
                                "Builtin " + builtin.fun() + " is not available for "
                                        + profile.target());
                    }
                }
                case Term.Lam lam -> pending.push(lam.body());
                case Term.Apply apply -> {
                    pending.push(apply.argument());
                    pending.push(apply.function());
                }
                case Term.Force force -> pending.push(force.term());
                case Term.Delay delay -> pending.push(delay.term());
                case Term.Constr constr -> {
                    requireConstrAndCase(programVersion, profile);
                    for (var field : constr.fields()) pending.push(field);
                }
                case Term.Case caseTerm -> {
                    requireConstrAndCase(programVersion, profile);
                    for (var branch : caseTerm.branches()) pending.push(branch);
                    pending.push(caseTerm.scrutinee());
                }
            }
        }
    }

    private static void requireConstrAndCase(
            UplcVersion programVersion, ProtocolFeatureProfile profile) {
        if (!programVersion.supportsConstrAndCase()) {
            throw new UnsupportedLedgerTargetException(
                    "Constr/Case terms require UPLC 1.1.0; program uses " + programVersion);
        }
        if (!profile.availableUplcVersions().contains(UplcVersion.V1_1_0)) {
            throw new UnsupportedLedgerTargetException(
                    "Constr/Case terms are not available for " + profile.target());
        }
    }
}
