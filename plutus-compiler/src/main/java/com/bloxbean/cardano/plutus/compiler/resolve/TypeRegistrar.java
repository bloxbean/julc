package com.bloxbean.cardano.plutus.compiler.resolve;

import com.bloxbean.cardano.plutus.compiler.CompilerException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;

import java.util.*;

/**
 * Collects records and sealed interfaces from multiple compilation units,
 * topologically sorts them by field-type dependencies, and registers in order.
 * <p>
 * This ensures that when a record {@code A} has a field of type {@code B},
 * {@code B} is registered before {@code A}.
 */
public class TypeRegistrar {

    private record TypeInfo(String name, RecordDeclaration record, ClassOrInterfaceDeclaration sealedInterface) {
        boolean isRecord() { return record != null; }
    }

    /**
     * Collect and register all types from the given compilation units.
     * Ledger types should already be registered in the TypeResolver before calling this.
     *
     * @param cus           the compilation units to scan
     * @param typeResolver  the type resolver to register into
     */
    public void registerAll(List<CompilationUnit> cus, TypeResolver typeResolver) {
        // 1. Collect all type declarations
        var allRecords = new LinkedHashMap<String, RecordDeclaration>();
        var allSealed = new LinkedHashMap<String, ClassOrInterfaceDeclaration>();

        for (var cu : cus) {
            for (var rd : cu.findAll(RecordDeclaration.class)) {
                var name = rd.getNameAsString();
                if (allRecords.containsKey(name)) {
                    throw new CompilerException("Duplicate record type '" + name + "' found across compilation units");
                }
                allRecords.put(name, rd);
            }
            for (var cd : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cd.isInterface() && !cd.getPermittedTypes().isEmpty()) {
                    var name = cd.getNameAsString();
                    if (allSealed.containsKey(name)) {
                        throw new CompilerException("Duplicate sealed interface '" + name + "' found across compilation units");
                    }
                    allSealed.put(name, cd);
                }
            }
        }

        // 2. Build dependency graph for records
        var recordDeps = new LinkedHashMap<String, Set<String>>();
        for (var entry : allRecords.entrySet()) {
            var deps = new LinkedHashSet<String>();
            for (var param : entry.getValue().getParameters()) {
                var typeName = param.getType().asString();
                // Strip generic parameters (List<X> → List, Map<X,Y> → Map, Optional<X> → Optional)
                var baseType = typeName.contains("<") ? typeName.substring(0, typeName.indexOf('<')) : typeName;
                if (allRecords.containsKey(baseType) && !baseType.equals(entry.getKey())) {
                    deps.add(baseType);
                }
            }
            recordDeps.put(entry.getKey(), deps);
        }

        // 3. Topological sort using Kahn's algorithm
        var sorted = topologicalSort(recordDeps);

        // 4. Register records in dependency order (skip already-registered ledger types)
        for (var name : sorted) {
            if (!typeResolver.isRegistered(name) && allRecords.containsKey(name)) {
                typeResolver.registerRecord(allRecords.get(name));
            }
        }

        // 5. Register sealed interfaces (their variants should already be registered as records)
        for (var entry : allSealed.entrySet()) {
            if (!typeResolver.isRegistered(entry.getKey())) {
                typeResolver.registerSealedInterface(entry.getValue());
            }
        }
    }

    private List<String> topologicalSort(Map<String, Set<String>> deps) {
        // Compute in-degree
        var inDegree = new LinkedHashMap<String, Integer>();
        for (var name : deps.keySet()) {
            inDegree.putIfAbsent(name, 0);
            for (var dep : deps.get(name)) {
                inDegree.merge(dep, 0, Integer::sum); // ensure dep exists
            }
        }
        // Count incoming edges
        for (var entry : deps.entrySet()) {
            for (var dep : entry.getValue()) {
                if (inDegree.containsKey(dep)) {
                    // dep must come before entry.getKey(), so entry has an incoming edge from dep
                }
            }
        }
        // Recompute properly
        inDegree.clear();
        for (var name : deps.keySet()) inDegree.put(name, 0);
        for (var entry : deps.entrySet()) {
            for (var dep : entry.getValue()) {
                // entry depends on dep → dep must come first → entry has incoming edge
                inDegree.merge(entry.getKey(), 1, Integer::sum);
            }
        }

        // Kahn's algorithm
        var queue = new ArrayDeque<String>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        var result = new ArrayList<String>();
        while (!queue.isEmpty()) {
            var node = queue.poll();
            result.add(node);
            // For each type that depends on node, decrement in-degree
            for (var entry : deps.entrySet()) {
                if (entry.getValue().contains(node)) {
                    var newDegree = inDegree.merge(entry.getKey(), -1, Integer::sum);
                    if (newDegree == 0) queue.add(entry.getKey());
                }
            }
        }

        if (result.size() != deps.size()) {
            var remaining = new LinkedHashSet<>(deps.keySet());
            remaining.removeAll(result);
            throw new CompilerException("Circular type dependency detected among: " + remaining);
        }

        return result;
    }
}
