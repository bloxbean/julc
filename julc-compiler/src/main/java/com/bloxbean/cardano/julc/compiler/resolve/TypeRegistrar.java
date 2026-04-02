package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.*;

/**
 * Collects records and sealed interfaces from multiple compilation units,
 * topologically sorts them by field-type dependencies, and registers in order.
 * <p>
 * This ensures that when a record {@code A} has a field of type {@code B},
 * {@code B} is registered before {@code A}. Sealed interfaces that are
 * referenced as field types (e.g., {@code List<ProofStep>}) are also
 * registered before the records that use them.
 * <p>
 * Types are keyed by FQCN (fully qualified class name). For packageless inline
 * code, the FQCN equals the simple name.
 * <p>
 * Supports both explicit {@code permits} clauses and implicit permits (sealed
 * interfaces where all variants are inner records implementing the interface).
 */
public class TypeRegistrar {

    /**
     * Collect and register all types from the given compilation units.
     * Any types already registered in the TypeResolver are skipped.
     *
     * @param cus           the compilation units to scan
     * @param typeResolver  the type resolver to register into
     */
    public void registerAll(List<CompilationUnit> cus, TypeResolver typeResolver) {
        // PASS 1: Collect all type declarations with FQCNs
        var allRecords = new LinkedHashMap<String, RecordDeclaration>();  // FQCN -> RD
        var allSealed = new LinkedHashMap<String, ClassOrInterfaceDeclaration>(); // FQCN -> CD
        var typeToCu = new LinkedHashMap<String, CompilationUnit>(); // FQCN -> source CU
        // For implicit permits: map sealed interface FQCN -> ordered list of inner variant FQCNs
        var implicitVariants = new LinkedHashMap<String, List<String>>();

        for (var cu : cus) {
            for (var rd : cu.findAll(RecordDeclaration.class)) {
                var simpleName = rd.getNameAsString();
                var fqcn = rd.getFullyQualifiedName().orElse(simpleName);

                if (allRecords.containsKey(fqcn)) {
                    throw errorAt(rd, "Duplicate record type '" + fqcn + "' found across compilation units");
                }
                allRecords.put(fqcn, rd);
                typeToCu.put(fqcn, cu);
            }
            for (var cd : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!cd.isInterface()) continue;

                boolean hasExplicitPermits = !cd.getPermittedTypes().isEmpty();
                boolean hasSealedModifier = cd.hasModifier(Modifier.Keyword.SEALED);

                if (hasExplicitPermits || hasSealedModifier) {
                    var simpleName = cd.getNameAsString();
                    var fqcn = cd.getFullyQualifiedName().orElse(simpleName);

                    if (allSealed.containsKey(fqcn)) {
                        throw errorAt(cd, "Duplicate sealed interface '" + fqcn + "' found across compilation units");
                    }
                    allSealed.put(fqcn, cd);
                    typeToCu.put(fqcn, cu);

                    // For implicit permits, find inner records implementing this interface
                    if (!hasExplicitPermits && hasSealedModifier) {
                        var variants = new ArrayList<String>();
                        for (var member : cd.getMembers()) {
                            if (member instanceof RecordDeclaration rd) {
                                boolean implementsParent = rd.getImplementedTypes().stream()
                                        .anyMatch(t -> t.getNameAsString().equals(simpleName));
                                if (implementsParent) {
                                    var variantFqcn = rd.getFullyQualifiedName()
                                            .orElse(fqcn + "." + rd.getNameAsString());
                                    variants.add(variantFqcn);
                                }
                            }
                        }
                        if (!variants.isEmpty()) {
                            implicitVariants.put(fqcn, variants);
                        }
                    }
                }
            }
        }

        // PASS 1b: Build knownFqcns = user FQCNs + already-registered FQCNs
        var knownFqcns = new LinkedHashSet<String>();
        knownFqcns.addAll(allRecords.keySet());
        knownFqcns.addAll(allSealed.keySet());
        knownFqcns.addAll(typeResolver.allRegisteredFqcns());
        // Hash type FQCNs are needed for ImportResolver but not registered as record types
        knownFqcns.addAll(TypeResolver.ledgerHashFqcns());

        // 2. Build unified dependency graph (records AND sealed interfaces) using FQCNs
        var allTypeNames = new LinkedHashSet<String>();
        allTypeNames.addAll(allRecords.keySet());
        allTypeNames.addAll(allSealed.keySet());

        // Also build a simple-name-to-FQCN index for dependency extraction
        var simpleToFqcn = new LinkedHashMap<String, Set<String>>();
        for (var fqcn : allTypeNames) {
            var simpleName = simpleName(fqcn);
            simpleToFqcn.computeIfAbsent(simpleName, k -> new LinkedHashSet<>()).add(fqcn);
        }

        var deps = new LinkedHashMap<String, Set<String>>();

        // Record dependencies: field types that are other records or sealed interfaces
        for (var entry : allRecords.entrySet()) {
            var fqcn = entry.getKey();
            var cu = typeToCu.get(fqcn);
            var importResolver = new ImportResolver(cu, knownFqcns);
            var typeDeps = new LinkedHashSet<String>();
            for (var param : entry.getValue().getParameters()) {
                extractTypeDependencies(param.getType().asString(), allTypeNames, simpleToFqcn, importResolver, typeDeps);
            }
            typeDeps.remove(fqcn);
            deps.put(fqcn, typeDeps);
        }

        // Sealed interface dependencies: their variant records must be registered first
        for (var entry : allSealed.entrySet()) {
            var fqcn = entry.getKey();
            var cu = typeToCu.get(fqcn);
            var importResolver = new ImportResolver(cu, knownFqcns);
            var typeDeps = new LinkedHashSet<String>();

            if (!entry.getValue().getPermittedTypes().isEmpty()) {
                // Explicit permits
                for (var permitted : entry.getValue().getPermittedTypes()) {
                    var resolvedVariant = resolveWithScope(permitted, allTypeNames, simpleToFqcn, importResolver);
                    if (allTypeNames.contains(resolvedVariant)) {
                        typeDeps.add(resolvedVariant);
                    }
                }
            } else {
                // Implicit permits: use inner variant records
                var variants = implicitVariants.get(fqcn);
                if (variants != null) {
                    for (var variantFqcn : variants) {
                        if (allTypeNames.contains(variantFqcn)) {
                            typeDeps.add(variantFqcn);
                        }
                    }
                }
            }
            deps.put(fqcn, typeDeps);
        }

        // 3. Topological sort the combined graph
        var sorted = topologicalSort(deps);

        // 4. Register in dependency order (skip already-registered types)
        for (var fqcn : sorted) {
            if (typeResolver.isRegistered(fqcn)) continue;

            // Set import context for field type resolution
            var cu = typeToCu.get(fqcn);
            if (cu != null) {
                typeResolver.setCurrentImportResolver(new ImportResolver(cu, knownFqcns));
            }

            if (allRecords.containsKey(fqcn)) {
                var rd = allRecords.get(fqcn);
                if (hasNewTypeAnnotation(rd)) {
                    validateNewType(rd);
                    PirType underlying = resolveUnderlyingType(rd);
                    var simpleName = rd.getNameAsString();
                    typeResolver.registerNewType(simpleName, fqcn, underlying);
                } else {
                    typeResolver.registerRecord(rd, fqcn);
                }
            } else if (allSealed.containsKey(fqcn)) {
                var cd = allSealed.get(fqcn);
                if (!cd.getPermittedTypes().isEmpty()) {
                    // Explicit permits — use standard registration
                    typeResolver.registerSealedInterface(cd, fqcn);
                } else {
                    // Implicit permits — register with inner variants
                    var variants = implicitVariants.get(fqcn);
                    if (variants != null) {
                        typeResolver.registerSealedInterfaceFromVariants(cd, fqcn, variants);
                    }
                }
            }
        }
        typeResolver.setCurrentImportResolver(null); // clean up
    }

    private static String simpleName(String fqcn) {
        var dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    /** Check if a record has the @NewType annotation. */
    private boolean hasNewTypeAnnotation(RecordDeclaration rd) {
        for (var ann : rd.getAnnotations()) {
            if (ann instanceof MarkerAnnotationExpr mae
                    && mae.getNameAsString().equals("NewType")) {
                return true;
            }
        }
        return false;
    }

    /** Validate that a @NewType record has exactly 1 field with a supported primitive type. */
    private void validateNewType(RecordDeclaration rd) {
        var params = rd.getParameters();
        if (params.size() != 1) {
            throw errorAt(rd, "@NewType record '" + rd.getNameAsString()
                    + "' must have exactly 1 field, got " + params.size());
        }
        var fieldType = params.get(0).getType().asString();
        if (!isSupportedNewTypeField(fieldType)) {
            throw errorAt(rd, "@NewType record '" + rd.getNameAsString()
                    + "' field type '" + fieldType + "' is not supported. "
                    + "Supported types: byte[], BigInteger, String, boolean");
        }
    }

    /** Resolve the underlying PIR type for a @NewType record's single field. */
    private PirType resolveUnderlyingType(RecordDeclaration rd) {
        var fieldType = rd.getParameters().get(0).getType().asString();
        return switch (fieldType) {
            case "byte[]" -> new PirType.ByteStringType();
            case "BigInteger" -> new PirType.IntegerType();
            case "String" -> new PirType.StringType();
            case "boolean" -> new PirType.BoolType();
            default -> throw new CompilerException("Unsupported @NewType field type: " + fieldType
                    + ". Supported types: byte[], BigInteger, String, boolean");
        };
    }

    private boolean isSupportedNewTypeField(String typeName) {
        return switch (typeName) {
            case "byte[]", "BigInteger", "String", "boolean" -> true;
            default -> false;
        };
    }

    /**
     * Extract type dependencies from a type string, including generic type arguments.
     * Uses ImportResolver to resolve simple names to FQCNs.
     */
    private void extractTypeDependencies(String typeName, Set<String> knownFqcns,
                                          Map<String, Set<String>> simpleToFqcn,
                                          ImportResolver importResolver,
                                          Set<String> deps) {
        if (typeName.contains("<")) {
            // Check base type
            String base = typeName.substring(0, typeName.indexOf('<'));
            resolveAndAddDep(base, knownFqcns, simpleToFqcn, importResolver, deps);
            // Extract and check generic arguments
            String argsStr = typeName.substring(typeName.indexOf('<') + 1, typeName.lastIndexOf('>'));
            for (String arg : splitTypeArgs(argsStr)) {
                extractTypeDependencies(arg.trim(), knownFqcns, simpleToFqcn, importResolver, deps);
            }
        } else {
            resolveAndAddDep(typeName, knownFqcns, simpleToFqcn, importResolver, deps);
        }
    }

    /**
     * Resolve a ClassOrInterfaceType using scope info, with fallback to resolveAndAddDep logic.
     */
    private String resolveWithScope(ClassOrInterfaceType ct, Set<String> knownTypes,
                                     Map<String, Set<String>> simpleToFqcn,
                                     ImportResolver importResolver) {
        if (ct.getScope().isPresent()) {
            var scopeResolved = resolveWithScope(ct.getScope().get(), knownTypes, simpleToFqcn, importResolver);
            var candidate = scopeResolved + "." + ct.getNameAsString();
            if (knownTypes.contains(candidate)) return candidate;
        }
        // Fall back to existing resolution chain
        var name = ct.getNameAsString();
        if (knownTypes.contains(name)) return name;
        try {
            var resolved = importResolver.resolve(name);
            if (knownTypes.contains(resolved)) return resolved;
        } catch (CompilerException ignored) {}
        var fqcns = simpleToFqcn.get(name);
        if (fqcns != null && fqcns.size() == 1) return fqcns.iterator().next();
        return name;
    }

    private void resolveAndAddDep(String name, Set<String> knownFqcns,
                                   Map<String, Set<String>> simpleToFqcn,
                                   ImportResolver importResolver, Set<String> deps) {
        // Try as FQCN first
        if (knownFqcns.contains(name)) {
            deps.add(name);
            return;
        }
        // Try resolving via ImportResolver
        try {
            var resolved = importResolver.resolve(name);
            if (knownFqcns.contains(resolved)) {
                deps.add(resolved);
                return;
            }
        } catch (CompilerException ignored) {
            // Ambiguity — will be caught later during type resolution
        }
        // Try simple name index (for packageless code)
        var fqcns = simpleToFqcn.get(name);
        if (fqcns != null && fqcns.size() == 1) {
            deps.add(fqcns.iterator().next());
        }
    }

    /**
     * Split generic type arguments, respecting nested angle brackets.
     * E.g., "A, Map<B, C>" -> ["A", "Map<B, C>"]
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
                // entry depends on dep -> entry has incoming edge
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

    /** Create a CompilerException with source position from a JavaParser node. */
    private CompilerException errorAt(Node node, String message) {
        int line = 0, col = 0;
        String fileName = "<source>";
        if (node != null) {
            var range = node.getRange();
            if (range.isPresent()) {
                line = range.get().begin.line;
                col = range.get().begin.column;
            }
            var cu = node.findCompilationUnit();
            if (cu.isPresent() && cu.get().getStorage().isPresent()) {
                fileName = cu.get().getStorage().get().getPath().toString();
            }
        }
        var diag = new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, message, fileName, line, col);
        return new CompilerException(List.of(diag));
    }
}
