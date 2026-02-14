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

    /**
     * Look up a name in the current (innermost) scope only.
     * Used to check if a variable was defined in the current scope level
     * (e.g., by switch pattern destructuring) rather than an outer scope.
     */
    public Optional<PirType> lookupCurrentScope(String name) {
        var type = scopes.peek().get(name);
        return type != null ? Optional.of(type) : Optional.empty();
    }

    /**
     * Returns all variables visible across all scopes (name → type).
     * If a name is defined in multiple scopes, the innermost (most recent) wins.
     * Used to snapshot pre-loop variables for re-binding after while loop compilation.
     */
    public Map<String, PirType> allVisibleVariables() {
        var result = new LinkedHashMap<String, PirType>();
        // Iterate from outermost to innermost so inner scopes override outer
        var scopeList = new ArrayList<>(scopes);
        for (int i = scopeList.size() - 1; i >= 0; i--) {
            result.putAll(scopeList.get(i));
        }
        return result;
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
