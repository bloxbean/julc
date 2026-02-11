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
 * {@code B} is registered before {@code A}. Sealed interfaces that are
 * referenced as field types (e.g., {@code List<ProofStep>}) are also
 * registered before the records that use them.
 */
public class TypeRegistrar {

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

        // 2. Build unified dependency graph (records AND sealed interfaces)
        var allTypeNames = new LinkedHashSet<String>();
        allTypeNames.addAll(allRecords.keySet());
        allTypeNames.addAll(allSealed.keySet());

        var deps = new LinkedHashMap<String, Set<String>>();

        // Record dependencies: field types that are other records or sealed interfaces
        for (var entry : allRecords.entrySet()) {
            var typeDeps = new LinkedHashSet<String>();
            for (var param : entry.getValue().getParameters()) {
                extractTypeDependencies(param.getType().asString(), allTypeNames, typeDeps);
            }
            typeDeps.remove(entry.getKey());
            deps.put(entry.getKey(), typeDeps);
        }

        // Sealed interface dependencies: their variant records must be registered first
        for (var entry : allSealed.entrySet()) {
            var typeDeps = new LinkedHashSet<String>();
            for (var permitted : entry.getValue().getPermittedTypes()) {
                var variantName = permitted.getNameAsString();
                if (allRecords.containsKey(variantName)) {
                    typeDeps.add(variantName);
                }
            }
            deps.put(entry.getKey(), typeDeps);
        }

        // 3. Topological sort the combined graph
        var sorted = topologicalSort(deps);

        // 4. Register in dependency order (skip already-registered ledger types)
        for (var name : sorted) {
            if (typeResolver.isRegistered(name)) continue;
            if (allRecords.containsKey(name)) {
                typeResolver.registerRecord(allRecords.get(name));
            } else if (allSealed.containsKey(name)) {
                typeResolver.registerSealedInterface(allSealed.get(name));
            }
        }
    }

    /**
     * Extract type dependencies from a type string, including generic type arguments.
     * For example, {@code List<ProofStep>} yields dependency on {@code ProofStep}.
     */
    private void extractTypeDependencies(String typeName, Set<String> knownTypes, Set<String> deps) {
        if (typeName.contains("<")) {
            // Check base type
            String base = typeName.substring(0, typeName.indexOf('<'));
            if (knownTypes.contains(base)) deps.add(base);
            // Extract and check generic arguments
            String argsStr = typeName.substring(typeName.indexOf('<') + 1, typeName.lastIndexOf('>'));
            for (String arg : splitTypeArgs(argsStr)) {
                extractTypeDependencies(arg.trim(), knownTypes, deps);
            }
        } else {
            if (knownTypes.contains(typeName)) deps.add(typeName);
        }
    }

    /**
     * Split generic type arguments, respecting nested angle brackets.
     * E.g., "A, Map<B, C>" → ["A", "Map<B, C>"]
     */
    private List<String> splitTypeArgs(String argsStr) {
        var result = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(argsStr.substring(start, i));
                start = i + 1;
            }
        }
        result.add(argsStr.substring(start));
        return result;
    }

    private List<String> topologicalSort(Map<String, Set<String>> deps) {
        // Compute in-degree
        var inDegree = new LinkedHashMap<String, Integer>();
        for (var name : deps.keySet()) inDegree.put(name, 0);
        for (var entry : deps.entrySet()) {
            for (var dep : entry.getValue()) {
                // entry depends on dep → entry has incoming edge
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
