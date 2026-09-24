package org.julclang.compiler.backend;

import org.julclang.compiler.*;
import org.julclang.compiler.codegen.ValidatorWrapper;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.*;

import java.util.*;

/** Language-neutral entry to the shared validator and UPLC pipeline. */
public final class CompilerBackend {
    /** Revision range, target features and descriptor capabilities for {@code options}. */
    public BackendCapabilities capabilities(CompilerOptions options) {
        return BackendContract.capabilities(CompilationContext.resolve(options));
    }

    /**
     * Compile a revision-2 function program (ADR-059): check revision and capabilities, verify
     * producer definitions and the entry, link trusted imports and lower without a wrapper.
     */
    public PirBackend.Result compile(FunctionProgram program, CompilerOptions options) {
        var context = CompilationContext.resolve(options);
        var capabilities = BackendContract.capabilities(context);
        String subject = "function program " + program.symbol();
        capabilities.requireRevision(program.revision(), BackendContract.REVISION_2, subject);
        capabilities.require(program.requiredCapabilities(), subject);
        requireTarget(program.target(), context, subject);
        var unit = ProgramUnit.prepare(subject, capabilities, program.namedTypes(),
                program.imports(), program.definitions());
        var verifier = unit.verifier(BackendContract.builtinCaseLowering(context));
        unit.verifyDefinitions(verifier);
        verifier.verify(program.symbol(), program.term(), program.type(), unit.environment());
        return PirBackend.lower(unit.link(new LinkedHashMap<>(), program.term()), context, null, true);
    }

    static void requireTarget(CompilerTarget produced, CompilationContext context, String subject) {
        if (!produced.equals(context.target()))
            throw new BackendException(DiagnosticCodes.BACKEND_TARGET_MISMATCH, subject, subject,
                    produced.profileId(), context.target().profileId());
    }

    public PirBackend.Result compile(FrontendProgram program, CompilerOptions options) {
        var context = CompilationContext.resolve(options);
        if (!program.target().equals(context.target()))
            throw new IllegalArgumentException("Frontend/backend target mismatch");
        if (!program.entrypoint().boundary().equals("julc-strict-v1"))
            throw new IllegalArgumentException(
                    "Unsupported boundary policy: " + program.entrypoint().boundary());
        PirClosure.check(program.term());
        var params = new ArrayList<PirType>();
        PirType result = program.type();
        while (result instanceof PirType.FunType fn) {
            params.add(fn.paramType());
            result = fn.returnType();
        }
        var term = program.term();
        var wrapper = new ValidatorWrapper(program.namedTypes());
        switch (program.entrypoint().purpose()) {
            case FUNCTION -> {}
            case SPEND -> {
                if (params.size() != 3
                        || !(result instanceof PirType.BoolType)
                        || !(params.get(2) instanceof PirType.DataType))
                    throw new IllegalArgumentException(
                            "Spending entrypoint requires datum -> redeemer -> ScriptContext ->"
                                + " Bool");
                term = wrapper.wrapSpendingValidator(term, 3, false, params.get(0), params.get(1));
            }
            case MINT -> {
                if (params.size() != 2
                        || !(result instanceof PirType.BoolType)
                        || !(params.get(1) instanceof PirType.DataType))
                    throw new IllegalArgumentException(
                            "Minting entrypoint requires redeemer -> ScriptContext -> Bool");
                term = wrapper.wrapMintingPolicy(term, params.get(0));
            }
        }
        return PirBackend.lower(term, context, null, true);
    }
}
