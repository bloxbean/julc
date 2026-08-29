package com.bloxbean.cardano.julc.compiler.uplc;

import com.bloxbean.cardano.julc.compiler.CompilationContext;
import com.bloxbean.cardano.julc.compiler.CompilerTargetDiagnostics;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultUni;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.ProtocolCapability;
import com.bloxbean.cardano.julc.vm.UplcVersion;

import java.util.ArrayDeque;
import java.util.Objects;

/** Final target-legality check for generated, and potentially optimized, UPLC. */
public final class UplcTargetValidator {

    private UplcTargetValidator() {
    }

    public static void validate(
            Program program,
            CompilationContext context,
            String producingStage) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(producingStage, "producingStage");

        var actualVersion = UplcVersion.from(program);
        if (!actualVersion.equals(context.target().uplcVersion())) {
            throw CompilerTargetDiagnostics.programVersionMismatch(context, actualVersion);
        }

        var profile = context.resolvedTarget().featureProfile();
        var pending = new ArrayDeque<Term>();
        pending.push(program.term());
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case Term.Var _, Term.Error _ -> {
                }
                case Term.Const constant -> validateConstant(
                        constant.value(), context, producingStage);
                case Term.Builtin builtin -> {
                    if (!profile.isBuiltinAvailable(builtin.fun())) {
                        throw CompilerTargetDiagnostics.invariantViolation(
                                context,
                                producingStage,
                                "builtin " + builtin.fun()
                                        + " (FLAT tag " + builtin.fun().flatCode() + ")");
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
                    requireCapability(
                            context, producingStage, ProtocolCapability.CONSTR_CASE);
                    for (var field : constr.fields()) pending.push(field);
                }
                case Term.Case caseTerm -> {
                    requireCapability(
                            context, producingStage, ProtocolCapability.CONSTR_CASE);
                    if (caseTerm.scrutinee() instanceof Term.Const
                            && !context.supports(
                                    ProtocolCapability.CASE_ON_BUILTIN_CONSTANTS)) {
                        throw CompilerTargetDiagnostics.invariantViolation(
                                context,
                                producingStage,
                                "case on builtin constant");
                    }
                    for (var branch : caseTerm.branches()) pending.push(branch);
                    pending.push(caseTerm.scrutinee());
                }
            }
        }
    }

    private static void validateConstant(
            Constant constant,
            CompilationContext context,
            String producingStage) {
        validateUni(constant.type(), context, producingStage);
        switch (constant) {
            case Constant.ListConst list -> list.values().forEach(
                    value -> validateConstant(value, context, producingStage));
            case Constant.PairConst pair -> {
                validateConstant(pair.first(), context, producingStage);
                validateConstant(pair.second(), context, producingStage);
            }
            case Constant.ArrayConst array -> array.values().forEach(
                    value -> validateConstant(value, context, producingStage));
            default -> {
            }
        }
    }

    private static void validateUni(
            DefaultUni uni,
            CompilationContext context,
            String producingStage) {
        switch (uni) {
            case DefaultUni.Bls12_381_G1_Element _,
                 DefaultUni.Bls12_381_G2_Element _,
                 DefaultUni.Bls12_381_MlResult _ -> requireCapability(
                    context, producingStage, ProtocolCapability.BLS_CONSTANTS);
            case DefaultUni.ProtoArray _ -> requireCapability(
                    context, producingStage, ProtocolCapability.ARRAY_CONSTANTS);
            case DefaultUni.ProtoValue _ -> requireCapability(
                    context, producingStage, ProtocolCapability.VALUE_CONSTANTS);
            case DefaultUni.Apply apply -> {
                validateUni(apply.f(), context, producingStage);
                validateUni(apply.arg(), context, producingStage);
            }
            default -> {
            }
        }
    }

    private static void requireCapability(
            CompilationContext context,
            String producingStage,
            ProtocolCapability capability) {
        if (!context.supports(capability)) {
            throw CompilerTargetDiagnostics.invariantViolation(
                    context, producingStage, capability.name());
        }
    }
}
