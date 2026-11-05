package org.julclang.compiler;

import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.source.SourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * ADR-060: on-chain methods are bound by name, so every call to an overloaded name runs one of its
 * declarations. Declarations sharing a name are accepted only when they lower to the same PIR with
 * the same type, and are therefore interchangeable; any other overload is rejected (JULC0054).
 * One instance checks the static methods of one class.
 */
public final class OnchainOverloads {
    private record Lowered(PirType type, PirTerm body) {}

    private final String owner;
    private final Set<String> entrypoints;
    private final Map<String, Lowered> seen = new HashMap<>();

    public OnchainOverloads(String owner, Set<String> entrypoints) {
        this.owner = owner;
        this.entrypoints = entrypoints;
    }

    public void check(String name, PirType type, PirTerm body, Supplier<SourceLocation> location) {
        var previous = seen.putIfAbsent(name, new Lowered(type, body));
        if (entrypoints.contains(name) || previous != null && !previous.equals(new Lowered(type, body))) {
            throw CompilerTypeDiagnostics.methodOverload(name, owner, location.get());
        }
    }
}
