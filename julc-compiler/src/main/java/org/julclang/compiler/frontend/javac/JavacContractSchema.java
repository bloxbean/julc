package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.JulcCompiler.ScriptPurpose;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema;

import java.util.Comparator;
import java.util.List;

/**
 * A validator's contract schema on the javac frontend (ADR-068 D7): the reference's {@code buildContractSchema}, read from
 * the compiled class's elements. Purposes, the order of the interfaces, which parameter is the datum and the redeemer, the
 * unwrapped {@code Optional} datum and the manual-dispatch rejection are the reference's. Types are javac's (D7), so a
 * schema names what the program lowers. Locations are javac's (I7), which only diagnostics print.
 */
final class JavacContractSchema {

    private final JavacFacts facts;

    JavacContractSchema(JavacFacts facts) {
        this.facts = facts;
    }

    /** The schema of a compiled validator; its bytes are already built, as in the reference. */
    ContractSchema of(ClassTree type, ScriptPurpose purpose, List<JavacDiscovery.EntryPoint> entrypoints,
                      List<JavacDiscovery.ParamField> params) {
        var parameters = params.stream()
                .map(param -> new ContractSchema.Argument(param.tree().getName().toString(), param.type(),
                        facts.location(param.tree())))
                .toList();
        var definitions = facts.registry().namedDefinitions();
        if (purpose == ScriptPurpose.MULTI) {
            if (entrypoints.stream().anyMatch(entry -> entry.tag() < 0)) {
                var at = facts.location(type);
                throw new CompilerException(List.of(new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR,
                        "Blueprint schema generation does not support manual multi-purpose "
                                + "dispatch for @MultiValidator. Use explicit purpose-indexed "
                                + "@Entrypoint methods or julc build --no-blueprint.",
                        at.fileName(), at.line(), at.column())));
            }
            var interfaces = entrypoints.stream()
                    .sorted(Comparator.comparingInt(JavacDiscovery.EntryPoint::tag))
                    .map(entry -> validatorInterface(ContractSchema.Purpose.valueOf(entry.purposeName()), entry.method()))
                    .toList();
            return new ContractSchema(interfaces, parameters, definitions, true);
        }
        return new ContractSchema(List.of(validatorInterface(purpose(purpose), entrypoints.getFirst().method())),
                parameters, definitions, false);
    }

    private ContractSchema.ValidatorInterface validatorInterface(ContractSchema.Purpose purpose, MethodTree method) {
        var parameters = method.getParameters();
        ContractSchema.Argument datum = null;
        ContractSchema.Argument redeemer;
        if (purpose == ContractSchema.Purpose.SPEND && parameters.size() == 3) {
            datum = argument(parameters.get(0), true);
            redeemer = argument(parameters.get(1), false);
        } else {
            redeemer = argument(parameters.get(0), false);
        }
        return new ContractSchema.ValidatorInterface(method.getName().toString(), purpose, datum, redeemer,
                facts.location(method));
    }

    /**
     * A datum or redeemer. A spending entry point's {@code Optional<T>} datum receives the ledger's Maybe datum; CIP-57
     * describes the datum attached to the output, the bare {@code T}.
     */
    private ContractSchema.Argument argument(VariableTree parameter, boolean unwrapOptional) {
        PirType type = facts.parameterType(parameter);
        if (unwrapOptional && type instanceof PirType.OptionalType optional) type = optional.elemType();
        return new ContractSchema.Argument(parameter.getName().toString(), type, facts.location(parameter));
    }

    private static ContractSchema.Purpose purpose(ScriptPurpose purpose) {
        return switch (purpose) {
            case MINTING -> ContractSchema.Purpose.MINT;
            case SPENDING -> ContractSchema.Purpose.SPEND;
            case WITHDRAW -> ContractSchema.Purpose.WITHDRAW;
            case CERTIFYING -> ContractSchema.Purpose.CERTIFY;
            case VOTING -> ContractSchema.Purpose.VOTE;
            case PROPOSING -> ContractSchema.Purpose.PROPOSE;
            case MULTI -> throw new IllegalArgumentException("Multi-purpose schemas require an entrypoint purpose");
        };
    }
}
