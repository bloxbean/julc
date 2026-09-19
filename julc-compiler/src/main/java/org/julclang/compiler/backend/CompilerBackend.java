package org.julclang.compiler.backend;

import org.julclang.compiler.*;
import org.julclang.compiler.codegen.ValidatorWrapper;
import org.julclang.compiler.pir.*;

import java.util.*;

/** Language-neutral entry to the shared validator and UPLC pipeline. */
public final class CompilerBackend {
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
