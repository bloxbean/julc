package com.bloxbean.cardano.julc.compiler.pir;

import java.util.List;
import java.util.Optional;

/**
 * Functional interface for resolving stdlib method calls to PIR terms.
 * <p>
 * Defined in the compiler module to avoid circular dependency with plutus-stdlib.
 * Implementations are provided by the stdlib module via StdlibRegistry.
 */
@FunctionalInterface
public interface StdlibLookup {
    /**
     * Look up a stdlib method and produce a PIR term for the given arguments.
     *
     * @param className  the simple class name (e.g., "ContextsLib")
     * @param methodName the method name (e.g., "signedBy")
     * @param args       the compiled argument PIR terms
     * @return the PIR term if found, empty otherwise
     */
    Optional<PirTerm> lookup(String className, String methodName, List<PirTerm> args);

    /**
     * Look up a stdlib method with arg type information for proper coercion.
     * Default delegates to untyped lookup. Override to insert decode/encode coercions
     * when caller arg types don't match callee parameter types.
     *
     * @param className  the simple class name
     * @param methodName the method name
     * @param args       the compiled argument PIR terms
     * @param argTypes   the PIR types of each argument at the call site
     * @return the PIR term if found, empty otherwise
     */
    default Optional<PirTerm> lookup(String className, String methodName,
                                      List<PirTerm> args, List<PirType> argTypes) {
        return lookup(className, methodName, args);
    }
}
