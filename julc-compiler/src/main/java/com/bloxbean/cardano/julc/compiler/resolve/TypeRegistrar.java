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
 * groups them by strongly connected field-type dependencies, and registers
 * those groups in dependency order.
 * <p>
 * Acyclic dependencies are registered before their consumers. Recursive
 * groups first receive nominal identities so self and mutual references can
 * be resolved without constructing an infinite {@link PirType} tree.
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

        // 3. Register dependency SCCs. Acyclic singleton groups retain the old path;
        // recursive groups first publish nominal identities for their internal references.
        for (var group : dependencyGroups(deps)) {
            boolean recursive = group.size() > 1
                    || deps.getOrDefault(group.getFirst(), Set.of()).contains(group.getFirst());
            if (!recursive) {
                registerType(group.getFirst(), allRecords, allSealed, implicitVariants,
                        typeToCu, knownFqcns, typeResolver);
                continue;
            }

            var pending = group.stream()
                    .filter(fqcn -> !typeResolver.isRegistered(fqcn))
                    .toList();
            try {
                for (String fqcn : pending) {
                    if (allRecords.containsKey(fqcn)) {
                        var rd = allRecords.get(fqcn);
                        if (hasNewTypeAnnotation(rd)) {
                            throw errorAt(rd, "@NewType cannot be recursive: '" + fqcn + "'");
                        }
                        typeResolver.predeclareNamedType(
                                fqcn, rd.getNameAsString(), PirType.NamedKind.RECORD);
                    } else {
                        var decl = allSealed.get(fqcn);
                        typeResolver.predeclareNamedType(
                                fqcn, decl.getNameAsString(), PirType.NamedKind.SUM);
                    }
                }

                // Variant records must be complete before their sealed sums collect fields.
                for (String fqcn : pending) {
                    if (allRecords.containsKey(fqcn)) {
                        registerType(fqcn, allRecords, allSealed, implicitVariants,
                                typeToCu, knownFqcns, typeResolver);
                    }
                }
                for (String fqcn : pending) {
                    if (allSealed.containsKey(fqcn)) {
                        registerType(fqcn, allRecords, allSealed, implicitVariants,
                                typeToCu, knownFqcns, typeResolver);
                    }
                }
                validateProductive(group, allRecords, allSealed, typeToCu, typeResolver);
            } catch (RuntimeException e) {
                typeResolver.discardForwardTypes(pending);
                throw e;
            }
        }
        typeResolver.setCurrentImportResolver(null); // clean up
    }

    private void registerType(
            String fqcn,
            Map<String, RecordDeclaration> allRecords,
            Map<String, ClassOrInterfaceDeclaration> allSealed,
            Map<String, List<String>> implicitVariants,
            Map<String, CompilationUnit> typeToCu,
            Set<String> knownFqcns,
            TypeResolver typeResolver) {
        if (typeResolver.isRegistered(fqcn)
                && typeResolver.resolveNameToType(fqcn).isPresent()
                && !(typeResolver.resolveNameToType(fqcn).get()
                        instanceof PirType.NamedTypeRef)) {
            return;
        }

        var cu = typeToCu.get(fqcn);
        if (cu != null) {
            typeResolver.setCurrentImportResolver(new ImportResolver(cu, knownFqcns));
        }

        if (allRecords.containsKey(fqcn)) {
            var rd = allRecords.get(fqcn);
            if (hasNewTypeAnnotation(rd)) {
                validateNewType(rd);
                PirType underlying = resolveUnderlyingType(rd);
                typeResolver.registerNewType(rd.getNameAsString(), fqcn, underlying);
            } else {
                typeResolver.registerRecord(rd, fqcn);
            }
        } else if (allSealed.containsKey(fqcn)) {
            var cd = allSealed.get(fqcn);
            if (!cd.getPermittedTypes().isEmpty()) {
                typeResolver.registerSealedInterface(cd, fqcn);
            } else {
                var variants = implicitVariants.get(fqcn);
                if (variants != null) {
                    typeResolver.registerSealedInterfaceFromVariants(cd, fqcn, variants);
                }
            }
        }
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

    private List<List<String>> dependencyGroups(Map<String, Set<String>> deps) {
        var index = new int[] {0};
        var indexes = new HashMap<String, Integer>();
        var lowLinks = new HashMap<String, Integer>();
        var stack = new ArrayDeque<String>();
        var onStack = new HashSet<String>();
        var components = new ArrayList<List<String>>();

        for (String node : deps.keySet()) {
            if (!indexes.containsKey(node)) {
                strongConnect(node, deps, index, indexes, lowLinks,
                        stack, onStack, components);
            }
        }

        var declarationOrder = new HashMap<String, Integer>();
        int position = 0;
        for (String node : deps.keySet()) declarationOrder.put(node, position++);
        components.forEach(component -> component.sort(
                Comparator.comparingInt(declarationOrder::get)));

        var componentOf = new HashMap<String, Integer>();
        for (int i = 0; i < components.size(); i++) {
            for (String node : components.get(i)) componentOf.put(node, i);
        }

        var componentDeps = new ArrayList<Set<Integer>>();
        var inDegree = new int[components.size()];
        for (int i = 0; i < components.size(); i++) componentDeps.add(new LinkedHashSet<>());
        for (var entry : deps.entrySet()) {
            int owner = componentOf.get(entry.getKey());
            for (String dependency : entry.getValue()) {
                int target = componentOf.get(dependency);
                if (owner != target && componentDeps.get(owner).add(target)) {
                    inDegree[owner]++;
                }
            }
        }

        Comparator<Integer> byDeclarationOrder = Comparator.comparingInt(component ->
                declarationOrder.get(components.get(component).getFirst()));
        var ready = new PriorityQueue<>(byDeclarationOrder);
        for (int i = 0; i < inDegree.length; i++) {
            if (inDegree[i] == 0) ready.add(i);
        }

        var result = new ArrayList<List<String>>();
        while (!ready.isEmpty()) {
            int completed = ready.remove();
            result.add(components.get(completed));
            for (int owner = 0; owner < componentDeps.size(); owner++) {
                if (componentDeps.get(owner).contains(completed)
                        && --inDegree[owner] == 0) {
                    ready.add(owner);
                }
            }
        }
        if (result.size() != components.size()) {
            throw new IllegalStateException("SCC condensation graph must be acyclic");
        }
        return result;
    }

    private void strongConnect(
            String node,
            Map<String, Set<String>> deps,
            int[] nextIndex,
            Map<String, Integer> indexes,
            Map<String, Integer> lowLinks,
            ArrayDeque<String> stack,
            Set<String> onStack,
            List<List<String>> components) {
        int nodeIndex = nextIndex[0]++;
        indexes.put(node, nodeIndex);
        lowLinks.put(node, nodeIndex);
        stack.push(node);
        onStack.add(node);

        for (String dependency : deps.getOrDefault(node, Set.of())) {
            if (!indexes.containsKey(dependency)) {
                strongConnect(dependency, deps, nextIndex, indexes, lowLinks,
                        stack, onStack, components);
                lowLinks.put(node, Math.min(lowLinks.get(node), lowLinks.get(dependency)));
            } else if (onStack.contains(dependency)) {
                lowLinks.put(node, Math.min(lowLinks.get(node), indexes.get(dependency)));
            }
        }

        if (lowLinks.get(node).equals(indexes.get(node))) {
            var component = new ArrayList<String>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(node));
            components.add(component);
        }
    }

    private void validateProductive(
            List<String> group,
            Map<String, RecordDeclaration> allRecords,
            Map<String, ClassOrInterfaceDeclaration> allSealed,
            Map<String, CompilationUnit> typeToCu,
            TypeResolver typeResolver) {
        var definitions = typeResolver.namedDefinitions();
        var productive = new LinkedHashSet<String>();
        boolean changed;
        do {
            changed = false;
            for (String fqcn : group) {
                if (!productive.contains(fqcn)
                        && isProductive(definitions.get(fqcn), productive)) {
                    productive.add(fqcn);
                    changed = true;
                }
            }
        } while (changed);

        if (productive.containsAll(group)) return;
        var unproductive = group.stream()
                .filter(name -> !productive.contains(name))
                .toList();
        String first = unproductive.getFirst();
        Node declaration = allRecords.containsKey(first)
                ? allRecords.get(first) : allSealed.get(first);
        throw errorAt(declaration,
                "Recursive type dependency has no finite base constructor: " + unproductive);
    }

    private boolean isProductive(PirType type, Set<String> productive) {
        if (type == null) return false;
        return switch (type) {
            case PirType.NamedTypeRef ref -> productive.contains(ref.stableId());
            case PirType.RecordType record -> record.fields().stream()
                    .allMatch(field -> isProductiveField(field.type(), productive));
            case PirType.SumType sum -> sum.constructors().stream()
                    .anyMatch(constructor -> constructor.fields().stream()
                            .allMatch(field -> isProductiveField(field.type(), productive)));
            default -> true;
        };
    }

    private boolean isProductiveField(PirType type, Set<String> productive) {
        // Empty/None container values are finite regardless of their element type.
        if (type instanceof PirType.ListType
                || type instanceof PirType.MapType
                || type instanceof PirType.OptionalType) {
            return true;
        }
        return isProductive(type, productive);
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
                fileName = cu.get().getStorage().get().getFileName();
            }
        }
        var diag = new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, message, fileName, line, col);
        return new CompilerException(List.of(diag));
    }
}
