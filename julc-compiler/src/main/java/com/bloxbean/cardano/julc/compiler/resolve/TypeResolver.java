package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VarType;
import com.github.javaparser.ast.type.VoidType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves Java types to PIR types.
 * <p>
 * Maps are keyed by FQCN (e.g., "com.bloxbean.cardano.julc.ledger.Value").
 * A simpleNameIndex provides fallback resolution for packageless inline code
 * and ambiguity detection when multiple types share the same simple name.
 */
public class TypeResolver {

    private static final String LEDGER_PKG = "com.bloxbean.cardano.julc.ledger";

    // Map of known record types (FQCN -> PirType.RecordType)
    private final Map<String, PirType.RecordType> recordTypes = new LinkedHashMap<>();
    // Map of known sum types (sealed interface FQCN -> PirType.SumType)
    private final Map<String, PirType.SumType> sumTypes = new LinkedHashMap<>();
    // Map of variant FQCN -> its enclosing SumType (for constructor tag lookup)
    private final Map<String, PirType.SumType> variantToSumType = new LinkedHashMap<>();
    // Map of @NewType FQCNs -> their underlying PIR type
    private final Map<String, PirType> newTypes = new LinkedHashMap<>();

    // Simple name -> set of FQCNs (for ambiguity detection + fallback)
    private final Map<String, Set<String>> simpleNameIndex = new LinkedHashMap<>();

    // Current import resolver (set per-CU during compilation)
    private ImportResolver currentImportResolver;

    private static final Set<String> LEDGER_HASH_NAMES = Set.of(
            "PubKeyHash", "ScriptHash", "ValidatorHash", "PolicyId", "TokenName", "DatumHash", "TxId");

    private static final Set<String> LEDGER_HASH_FQCNS = LEDGER_HASH_NAMES.stream()
            .map(n -> LEDGER_PKG + "." + n).collect(Collectors.toUnmodifiableSet());

    /** Return the set of ledger hash FQCNs (needed for ImportResolver knownFqcns). */
    public static Set<String> ledgerHashFqcns() {
        return LEDGER_HASH_FQCNS;
    }

    /** Return the set of ledger hash simple names. */
    public static Set<String> ledgerHashNames() {
        return LEDGER_HASH_NAMES;
    }


    public void setCurrentImportResolver(ImportResolver resolver) {
        this.currentImportResolver = resolver;
    }

    public void registerRecord(RecordDeclaration rd, String fqcn) {
        var name = rd.getNameAsString();
        if (recordTypes.containsKey(fqcn)) {
            throw new IllegalArgumentException("Duplicate record type: '" + fqcn + "'. "
                    + "A record with this name is already registered.");
        }
        var fields = new ArrayList<PirType.Field>();
        for (var param : rd.getParameters()) {
            fields.add(new PirType.Field(param.getNameAsString(), resolve(param.getType())));
        }
        var recordType = new PirType.RecordType(name, fields);
        recordTypes.put(fqcn, recordType);
        simpleNameIndex.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(fqcn);
    }

    /** Check if a type name is already registered (as record, sealed interface, or newtype). */
    public boolean isRegistered(String fqcnOrSimpleName) {
        // Direct FQCN check
        if (recordTypes.containsKey(fqcnOrSimpleName) || sumTypes.containsKey(fqcnOrSimpleName)
                || newTypes.containsKey(fqcnOrSimpleName) || variantToSumType.containsKey(fqcnOrSimpleName)) {
            return true;
        }
        // Simple name check via index
        return simpleNameIndex.containsKey(fqcnOrSimpleName)
                || LEDGER_HASH_NAMES.contains(fqcnOrSimpleName);
    }

    /**
     * Register a @NewType record. On-chain, it resolves to the underlying type
     * and its constructor/of() are identity operations.
     */
    public void registerNewType(String name, String fqcn, PirType underlyingType) {
        newTypes.put(fqcn, underlyingType);
        simpleNameIndex.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(fqcn);
    }

    /** Check if a type name is a registered @NewType. */
    public boolean isNewType(String fqcnOrSimpleName) {
        if (newTypes.containsKey(fqcnOrSimpleName)) return true;
        String resolved = resolveName(fqcnOrSimpleName);
        return newTypes.containsKey(resolved);
    }

    /** Get all registered @NewType names (returns simple names for backward compat). */
    public Set<String> getNewTypeNames() {
        // Return simple names for backward compatibility with wrapWithNewTypeLookup
        var simpleNames = new LinkedHashSet<String>();
        for (var fqcn : newTypes.keySet()) {
            var dot = fqcn.lastIndexOf('.');
            simpleNames.add(dot >= 0 ? fqcn.substring(dot + 1) : fqcn);
        }
        return Collections.unmodifiableSet(simpleNames);
    }

    public void registerSealedInterface(ClassOrInterfaceDeclaration decl, String fqcn) {
        var interfaceName = decl.getNameAsString();
        if (sumTypes.containsKey(fqcn)) {
            throw new IllegalArgumentException("Duplicate sealed interface: '" + fqcn + "'. "
                    + "A sealed interface with this name is already registered.");
        }
        var constructors = new ArrayList<PirType.Constructor>();
        var resolvedVariantFqcns = new ArrayList<String>();
        int tag = 0;
        for (var permittedType : decl.getPermittedTypes()) {
            var variantName = permittedType.getNameAsString();
            // Look up the record type for this variant (must already be registered)
            // Use scope-aware resolution for inner class variants (e.g., ProofStep.Branch)
            var variantFqcn = resolveTypeWithScope(permittedType);
            resolvedVariantFqcns.add(variantFqcn);
            var recordType = recordTypes.get(variantFqcn);
            List<PirType.Field> fields = recordType != null ? recordType.fields() : List.of();
            constructors.add(new PirType.Constructor(variantName, tag, fields));
            tag++;
        }
        var sumType = new PirType.SumType(interfaceName, constructors);
        sumTypes.put(fqcn, sumType);
        simpleNameIndex.computeIfAbsent(interfaceName, k -> new LinkedHashSet<>()).add(fqcn);
        // Register each variant -> sum type mapping using resolved FQCNs
        for (int i = 0; i < constructors.size(); i++) {
            var ctor = constructors.get(i);
            var resolvedFqcn = resolvedVariantFqcns.get(i);
            var actualFqcn = resolvedFqcn.contains(".")
                    ? resolvedFqcn
                    : fqcn.substring(0, fqcn.lastIndexOf('.') + 1) + ctor.name();
            variantToSumType.put(actualFqcn, sumType);
            simpleNameIndex.computeIfAbsent(ctor.name(), k -> new LinkedHashSet<>()).add(actualFqcn);
        }
    }

    /**
     * Register a sealed interface using an ordered list of variant FQCNs (for implicit permits).
     * Used when the sealed interface has no explicit {@code permits} clause
     * and variants are discovered as inner records.
     *
     * @param decl         the sealed interface declaration
     * @param fqcn         the FQCN of the sealed interface
     * @param variantFqcns ordered list of variant FQCNs (determines constructor tags)
     */
    public void registerSealedInterfaceFromVariants(ClassOrInterfaceDeclaration decl, String fqcn,
                                                     List<String> variantFqcns) {
        var interfaceName = decl.getNameAsString();
        if (sumTypes.containsKey(fqcn)) {
            throw new IllegalArgumentException("Duplicate sealed interface: '" + fqcn + "'. "
                    + "A sealed interface with this name is already registered.");
        }
        var constructors = new ArrayList<PirType.Constructor>();
        int tag = 0;
        for (var variantFqcn : variantFqcns) {
            var variantName = variantFqcn.substring(variantFqcn.lastIndexOf('.') + 1);
            var recordType = recordTypes.get(variantFqcn);
            List<PirType.Field> fields = recordType != null ? recordType.fields() : List.of();
            constructors.add(new PirType.Constructor(variantName, tag, fields));
            tag++;
        }
        var sumType = new PirType.SumType(interfaceName, constructors);
        sumTypes.put(fqcn, sumType);
        simpleNameIndex.computeIfAbsent(interfaceName, k -> new LinkedHashSet<>()).add(fqcn);
        // Register each variant -> sum type mapping
        for (int i = 0; i < variantFqcns.size(); i++) {
            var ctor = constructors.get(i);
            var variantFqcn = variantFqcns.get(i);
            variantToSumType.put(variantFqcn, sumType);
            simpleNameIndex.computeIfAbsent(ctor.name(), k -> new LinkedHashSet<>()).add(variantFqcn);
        }
    }

    /**
     * Add flat-FQCN aliases for inner variant types under a given package.
     * For example, maps "pkg.Credential.PubKeyCredential" also under "pkg.PubKeyCredential".
     * This ensures backward compatibility with ImportResolver wildcard resolution.
     *
     * @param packagePrefix the package prefix (e.g., "com.bloxbean.cardano.julc.ledger")
     */
    public void addFlatVariantAliases(String packagePrefix) {
        var aliases = new LinkedHashMap<String, PirType.SumType>();
        for (var entry : new LinkedHashMap<>(variantToSumType).entrySet()) {
            String fqcn = entry.getKey();
            if (fqcn.startsWith(packagePrefix + ".")) {
                String afterPkg = fqcn.substring(packagePrefix.length() + 1);
                if (afterPkg.contains(".")) {
                    // Inner class FQCN, e.g., "Credential.PubKeyCredential"
                    String simpleName = afterPkg.substring(afterPkg.lastIndexOf('.') + 1);
                    String flatFqcn = packagePrefix + "." + simpleName;
                    // Skip if flat FQCN already exists as a top-level type (e.g., Vote is both
                    // a sealed interface and a variant of Delegatee — don't shadow the interface)
                    if (!sumTypes.containsKey(flatFqcn)
                            && !recordTypes.containsKey(flatFqcn)
                            && !variantToSumType.containsKey(flatFqcn)) {
                        aliases.put(flatFqcn, entry.getValue());
                    } else {
                        // Conflict: inner variant's simple name clashes with a top-level type.
                        // Remove the inner-class FQCN from simpleNameIndex so the top-level type
                        // wins for simple-name resolution (e.g., "Vote" → Vote interface, not Delegatee.Vote)
                        var fqcns = simpleNameIndex.get(simpleName);
                        if (fqcns != null) {
                            fqcns.remove(fqcn);
                        }
                    }
                }
            }
        }
        for (var entry : aliases.entrySet()) {
            variantToSumType.put(entry.getKey(), entry.getValue());
            String simpleName = entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1);
            simpleNameIndex.computeIfAbsent(simpleName, k -> new LinkedHashSet<>()).add(entry.getKey());
        }
    }

    public Optional<PirType.SumType> lookupSumType(String fqcnOrSimpleName) {
        var result = sumTypes.get(fqcnOrSimpleName);
        if (result != null) return Optional.of(result);
        String resolved = resolveName(fqcnOrSimpleName);
        return Optional.ofNullable(sumTypes.get(resolved));
    }

    public Optional<PirType.SumType> lookupSumTypeForVariant(String fqcnOrSimpleName) {
        var result = variantToSumType.get(fqcnOrSimpleName);
        if (result != null) return Optional.of(result);
        String resolved = resolveName(fqcnOrSimpleName);
        return Optional.ofNullable(variantToSumType.get(resolved));
    }

    public PirType resolve(Type type) {
        if (type instanceof PrimitiveType pt) {
            return switch (pt.getType()) {
                case BOOLEAN -> new PirType.BoolType();
                case INT, LONG -> new PirType.IntegerType();
                default -> throw new IllegalArgumentException("Unsupported primitive type: " + pt);
            };
        }
        if (type instanceof VoidType) {
            return new PirType.UnitType();
        }
        if (type instanceof ClassOrInterfaceType ct) {
            return resolveClassType(ct);
        }
        if (type instanceof VarType) {
            // 'var' declarations - type will be inferred by PirGenerator from initializer
            return new PirType.DataType(); // placeholder, PirGenerator overrides this
        }
        // byte[] is represented as ArrayType
        if (type instanceof com.github.javaparser.ast.type.ArrayType at) {
            if (at.getComponentType() instanceof PrimitiveType pt &&
                    pt.getType() == PrimitiveType.Primitive.BYTE) {
                return new PirType.ByteStringType();
            }
            throw new IllegalArgumentException("Arrays are not supported; use List. Got: " + at);
        }
        if (type instanceof com.github.javaparser.ast.type.UnknownType) {
            return new PirType.DataType(); // default fallback for untyped lambda params
        }
        throw new IllegalArgumentException("Cannot resolve type: " + type);
    }

    private PirType resolveClassType(ClassOrInterfaceType ct) {
        var name = ct.getNameAsString();

        // Built-in Java types: always by simple name (these never have FQCN variants)
        return switch (name) {
            case "BigInteger" -> new PirType.IntegerType();
            case "String" -> new PirType.StringType();
            case "Boolean" -> new PirType.BoolType();
            case "PlutusData", "ConstrData", "MapData", "ListData", "IntData", "BytesData" -> new PirType.DataType();
            case "List", "JulcList" -> {
                PirType elemType = new PirType.DataType();
                var listArgs = ct.getTypeArguments();
                if (listArgs.isPresent() && !listArgs.get().isEmpty()) {
                    elemType = resolve(listArgs.get().get(0));
                }
                yield new PirType.ListType(elemType);
            }
            case "Map", "JulcMap" -> {
                PirType keyType = new PirType.DataType();
                PirType valType = new PirType.DataType();
                var mapArgs = ct.getTypeArguments();
                if (mapArgs.isPresent()) {
                    var args = mapArgs.get();
                    if (args.size() >= 1) keyType = resolve(args.get(0));
                    if (args.size() >= 2) valType = resolve(args.get(1));
                }
                yield new PirType.MapType(keyType, valType);
            }
            case "Tuple2" -> {
                PirType firstType = new PirType.DataType();
                PirType secondType = new PirType.DataType();
                var tupleArgs = ct.getTypeArguments();
                if (tupleArgs.isPresent() && tupleArgs.get().size() >= 2) {
                    firstType = resolve(tupleArgs.get().get(0));
                    secondType = resolve(tupleArgs.get().get(1));
                }
                yield new PirType.RecordType("Tuple2", List.of(
                        new PirType.Field("first", firstType),
                        new PirType.Field("second", secondType)));
            }
            case "Tuple3" -> {
                PirType firstType = new PirType.DataType();
                PirType secondType = new PirType.DataType();
                PirType thirdType = new PirType.DataType();
                var tuple3Args = ct.getTypeArguments();
                if (tuple3Args.isPresent() && tuple3Args.get().size() >= 3) {
                    firstType = resolve(tuple3Args.get().get(0));
                    secondType = resolve(tuple3Args.get().get(1));
                    thirdType = resolve(tuple3Args.get().get(2));
                }
                yield new PirType.RecordType("Tuple3", List.of(
                        new PirType.Field("first", firstType),
                        new PirType.Field("second", secondType),
                        new PirType.Field("third", thirdType)));
            }
            case "AssetEntry" -> new PirType.RecordType("AssetEntry", List.of(
                    new PirType.Field("policyId", new PirType.ByteStringType()),
                    new PirType.Field("tokenName", new PirType.ByteStringType()),
                    new PirType.Field("amount", new PirType.IntegerType())));
            case "Optional" -> {
                PirType elemType = new PirType.DataType();
                var optArgs = ct.getTypeArguments();
                if (optArgs.isPresent() && !optArgs.get().isEmpty()) {
                    elemType = resolve(optArgs.get().get(0));
                }
                yield new PirType.OptionalType(elemType);
            }
            default -> {
                // Resolve using scope info when available (e.g., ProofStep.Branch)
                String fqcn = resolveTypeWithScope(ct);

                // Check ledger hash types -> ByteStringType
                if (LEDGER_HASH_FQCNS.contains(fqcn) || LEDGER_HASH_NAMES.contains(name)) yield new PirType.ByteStringType();
                // Check @NewType records -> resolve to underlying type
                if (newTypes.containsKey(fqcn)) yield newTypes.get(fqcn);
                // Check registered record types (includes dynamically registered ledger records)
                if (recordTypes.containsKey(fqcn)) yield recordTypes.get(fqcn);
                // Check registered sum types (sealed interfaces, including ledger sum types)
                if (sumTypes.containsKey(fqcn)) yield sumTypes.get(fqcn);
                // Check if FQCN is a variant of a registered sealed interface
                if (variantToSumType.containsKey(fqcn)) {
                    var st = variantToSumType.get(fqcn);
                    var variantName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
                    for (var ctor : st.constructors()) {
                        if (ctor.name().equals(variantName)) {
                            yield new PirType.RecordType(ctor.name(), ctor.fields());
                        }
                    }
                }
                throw new IllegalArgumentException("Unknown type: " + name
                        + (fqcn.equals(name) ? "" : " (resolved to " + fqcn + ")"));
            }
        };
    }

    /**
     * Resolve a ClassOrInterfaceType to FQCN using scope info when available.
     * For "ProofStep.Branch": resolves scope "ProofStep" → "com.a.ProofStep",
     * then returns "com.a.ProofStep.Branch".
     * Falls back to resolveName(simpleName) when no scope present.
     */
    public String resolveTypeWithScope(ClassOrInterfaceType ct) {
        if (ct.getScope().isPresent()) {
            var scopeFqcn = resolveTypeWithScope(ct.getScope().get());
            var candidate = scopeFqcn + "." + ct.getNameAsString();
            // Check if candidate exists in any registry
            if (recordTypes.containsKey(candidate) || sumTypes.containsKey(candidate)
                    || variantToSumType.containsKey(candidate) || newTypes.containsKey(candidate)) {
                return candidate;
            }
            // Check simpleNameIndex for the constructed name
            var fqcns = simpleNameIndex.get(ct.getNameAsString());
            if (fqcns != null && fqcns.contains(candidate)) {
                return candidate;
            }
            // Scope resolved but candidate not found — fall through to standard resolution
        }
        return resolveName(ct.getNameAsString());
    }

    /**
     * Central name resolution: simple name -> FQCN.
     */
    String resolveName(String simpleName) {
        // If it already looks like a FQCN, return as-is
        if (simpleName.contains(".")) return simpleName;

        // 1. Try ImportResolver (if set)
        if (currentImportResolver != null) {
            var resolved = currentImportResolver.resolve(simpleName);
            if (!resolved.equals(simpleName)) {
                // ImportResolver found a match
                return resolved;
            }
            // ImportResolver returned unresolved — fall through to simpleNameIndex
        }
        // 2. Fallback: check simpleNameIndex (covers inner classes, packageless types)
        var fqcns = simpleNameIndex.get(simpleName);
        if (fqcns != null && fqcns.size() == 1) return fqcns.iterator().next();
        if (fqcns != null && fqcns.size() > 1) {
            throw new IllegalArgumentException("Ambiguous type '" + simpleName
                    + "'. Could be: " + String.join(", ", fqcns)
                    + ". Use an explicit import to disambiguate.");
        }
        // 3. No match: return simple name (for packageless code)
        return simpleName;
    }

    /**
     * Resolve a class name for library/stdlib lookup.
     */
    public String resolveClassName(String simpleName) {
        if (currentImportResolver != null) {
            return currentImportResolver.resolve(simpleName);
        }
        return simpleName;
    }

    public Optional<PirType.RecordType> lookupRecord(String fqcnOrSimpleName) {
        var result = recordTypes.get(fqcnOrSimpleName);
        if (result != null) return Optional.of(result);
        String resolved = resolveName(fqcnOrSimpleName);
        return Optional.ofNullable(recordTypes.get(resolved));
    }

    /**
     * Resolve a FQCN (or simple name) to its PirType.
     * Returns empty for unknown types or built-in Java types (BigInteger, List, etc.).
     */
    public Optional<PirType> resolveNameToType(String fqcnOrSimpleName) {
        String fqcn = resolveName(fqcnOrSimpleName);
        if (LEDGER_HASH_FQCNS.contains(fqcn) || LEDGER_HASH_NAMES.contains(fqcnOrSimpleName))
            return Optional.of(new PirType.ByteStringType());
        if (newTypes.containsKey(fqcn)) return Optional.of(newTypes.get(fqcn));
        if (recordTypes.containsKey(fqcn)) return Optional.of(recordTypes.get(fqcn));
        if (sumTypes.containsKey(fqcn)) return Optional.of(sumTypes.get(fqcn));
        return Optional.empty();
    }

    /** Return all registered FQCNs (for building knownFqcns sets). */
    public Set<String> allRegisteredFqcns() {
        var all = new LinkedHashSet<String>();
        all.addAll(recordTypes.keySet());
        all.addAll(sumTypes.keySet());
        all.addAll(variantToSumType.keySet());
        all.addAll(newTypes.keySet());
        return all;
    }
}
