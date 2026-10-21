package org.julclang.compiler.backend;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.codegen.StrictBoundaryGenerator;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.Program;

import java.util.Map;

/**
 * Standalone strict boundary checks (ADR-059). Deployment parameters are decoded but not
 * validated on-chain, like Java {@code @Param} fields; a consumer can evaluate this program
 * with any VM before {@code Program.applyParams} to validate parameter Data at no on-chain
 * cost. It uses the same {@link StrictBoundaryGenerator} as validator datums and redeemers.
 */
public final class BoundaryPrograms {
    private BoundaryPrograms() {}

    /**
     * A one-argument program that succeeds exactly when its Data argument is a well-formed
     * encoding of {@code type}, and fails otherwise.
     *
     * @param type       the parameter, datum or redeemer type
     * @param namedTypes named type definitions referenced by {@code type}
     * @param options    compiler options selecting the target
     */
    public static Program check(PirType type, Map<String, PirType> namedTypes, CompilerOptions options) {
        var context = CompilationContext.resolve(options);
        String subject = "boundary check for " + PirVerifier.show(type);
        if (resolve(type, namedTypes) instanceof PirType.UnitType)
            throw new BackendException(org.julclang.compiler.error.DiagnosticCodes.BACKEND_INVALID_DESCRIPTOR,
                    subject, subject, "Unit has no parameter decoding; check the Data type instead");
        ValidatorCompiler.requireBoundary(type, namedTypes, ValidatorCompiler.uncheckable(java.util.List.of()),
                subject, "type");
        var data = new PirTerm.Var("$julc$data", new PirType.DataType());
        var check = new StrictBoundaryGenerator(namedTypes).check(data, type);
        var term = new PirTerm.Lam("$julc$data", new PirType.DataType(), new PirTerm.IfThenElse(check,
                new PirTerm.Const(Constant.unit()), new PirTerm.Error(new PirType.UnitType())));
        return PirBackend.lower(term, context, null, true).program();
    }

    private static PirType resolve(PirType type, Map<String, PirType> namedTypes) {
        return type instanceof PirType.NamedTypeRef ref ? namedTypes.getOrDefault(ref.stableId(), type) : type;
    }
}
