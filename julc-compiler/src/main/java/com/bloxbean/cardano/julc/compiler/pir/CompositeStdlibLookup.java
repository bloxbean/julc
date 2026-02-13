package com.bloxbean.cardano.julc.compiler.pir;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Chains multiple {@link StdlibLookup} instances. First match wins.
 * Used to compose the standard stdlib registry with library method lookups.
 */
public class CompositeStdlibLookup implements StdlibLookup {

    private final List<StdlibLookup> lookups;

    public CompositeStdlibLookup(StdlibLookup... lookups) {
        this.lookups = Arrays.stream(lookups)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Return the underlying lookups for inspection (e.g., to find LibraryMethodRegistry).
     */
    public List<StdlibLookup> getLookups() {
        return lookups;
    }

    @Override
    public Optional<PirTerm> lookup(String className, String methodName, List<PirTerm> args) {
        for (var lookup : lookups) {
            var result = lookup.lookup(className, methodName, args);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<PirTerm> lookup(String className, String methodName,
                                      List<PirTerm> args, List<PirType> argTypes) {
        for (var lookup : lookups) {
            var result = lookup.lookup(className, methodName, args, argTypes);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }
}
