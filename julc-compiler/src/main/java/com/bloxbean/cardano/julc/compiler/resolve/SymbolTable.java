package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;

import java.util.*;

/**
 * Scope management for variable and method lookups during compilation.
 */
public class SymbolTable {

    private final Deque<Map<String, PirType>> scopes = new ArrayDeque<>();
    private final Map<String, MethodInfo> methods = new LinkedHashMap<>();

    public SymbolTable() {
        pushScope(); // global scope
    }

    public void pushScope() {
        scopes.push(new LinkedHashMap<>());
    }

    public void popScope() {
        if (scopes.size() <= 1) throw new IllegalStateException("Cannot pop global scope");
        scopes.pop();
    }

    public void define(String name, PirType type) {
        scopes.peek().put(name, type);
    }

    public Optional<PirType> lookup(String name) {
        for (var scope : scopes) {
            var type = scope.get(name);
            if (type != null) return Optional.of(type);
        }
        return Optional.empty();
    }

    public PirType require(String name) {
        return lookup(name).orElseThrow(
                () -> new NoSuchElementException("Undefined variable: " + name));
    }

    // --- Helper method support ---

    public void defineMethod(String name, PirType type, PirTerm body) {
        methods.put(name, new MethodInfo(name, type, body));
        // Also register in global scope so variable lookups find it
        scopes.peekLast().put(name, type);
    }

    public Optional<MethodInfo> lookupMethod(String name) {
        return Optional.ofNullable(methods.get(name));
    }

    public Collection<MethodInfo> allMethods() {
        return Collections.unmodifiableCollection(methods.values());
    }

    public record MethodInfo(String name, PirType type, PirTerm body) {}
}
