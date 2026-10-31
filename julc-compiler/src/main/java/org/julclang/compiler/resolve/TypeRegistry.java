package org.julclang.compiler.resolve;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The named types of a compilation, by identity (ADR-068 D2, I12): records, sealed interfaces and their variants, and
 * {@code @NewType}s, each keyed by FQCN. This class is frontend-neutral. It knows no source syntax and has no lookup by
 * simple name, so code typed on it cannot bind a name the way a Java reader must not. {@link TypeResolver} adds the
 * JavaParser frontend's source-side resolution on top of it; the javac frontend uses this class alone, and the compiler
 * enforces that.
 */
public class TypeRegistry {

    private static final String LEDGER_PKG = "org.julclang.ledger";

    // Map of known record types (FQCN -> PirType.RecordType)
    final Map<String, PirType.RecordType> recordTypes = new LinkedHashMap<>();
    // Map of known sum types (sealed interface FQCN -> PirType.SumType)
    final Map<String, PirType.SumType> sumTypes = new LinkedHashMap<>();
    // Map of variant FQCN -> its enclosing SumType (for constructor tag lookup)
    final Map<String, PirType.SumType> variantToSumType = new LinkedHashMap<>();
    // Sealed interfaces that declare type parameters; type arguments are dropped, so a switch over
    // one cannot be checked (ADR-067 D4, JULC0007)
    final Set<String> parameterizedSumTypes = new LinkedHashSet<>();
    // Every sealed interface a variant FQCN was registered under, in order; variantToSumType keeps only the last
    // (a record with two sealed parents, #279, rejected by ADR-067 D6). Used to resolve case patterns (F64).
    final Map<String, List<PirType.SumType>> variantFamilies = new LinkedHashMap<>();
    // Map of @NewType FQCNs -> their underlying PIR type
    final Map<String, PirType> newTypes = new LinkedHashMap<>();
    // Forward identities visible only while one recursive declaration SCC is registered.
    final Map<String, PirType.NamedTypeRef> forwardTypes = new LinkedHashMap<>();

    /** The simple names declared so far, in order: only ever a "did you mean" suggestion, never a binding. */
    private final Set<String> suggestions = new LinkedHashSet<>();

    static final Set<String> LEDGER_HASH_NAMES = Set.of(
            "PubKeyHash", "ScriptHash", "ValidatorHash", "PolicyId", "TokenName", "DatumHash", "TxId");

    static final Set<String> LEDGER_HASH_FQCNS = LEDGER_HASH_NAMES.stream()
            .map(n -> LEDGER_PKG + "." + n).collect(Collectors.toUnmodifiableSet());

    private static final List<String> BUILTIN_TYPE_NAMES = List.of(
            "BigInteger", "String", "Boolean", "PlutusData",
            "List", "Map", "Optional", "Tuple2", "Tuple3");

    /** Return the set of ledger hash FQCNs (needed for ImportResolver knownFqcns). */
    public static Set<String> ledgerHashFqcns() {
        return LEDGER_HASH_FQCNS;
    }

    /** Return the set of ledger hash simple names. */
    public static Set<String> ledgerHashNames() {
        return LEDGER_HASH_NAMES;
    }

    /** One variant of a sealed interface, in tag order. */
    public record SealedVariant(String name, String fqcn) {}

    /** Note that {@code fqcn} is declared under the simple name {@code name}. */
    void declared(String name, String fqcn) {
        suggestions.add(name);
    }

    /** Forget {@link #declared} for a failed recursive registration. */
    void undeclared(String name, String fqcn) {
        suggestions.remove(name);
    }

    /**
     * Register a record whose components are already resolved, nominally (ADR-068 D7).
     *
     * @param name   the record's simple name
     * @param fqcn   its identity
     * @param fields its components, in declaration order
     */
    public void registerRecord(String name, String fqcn, List<PirType.Field> fields) {
        if (recordTypes.containsKey(fqcn)) {
            throw new CompilerException("Duplicate record type: '" + fqcn + "'. "
                    + "A record with this name is already registered.");
        }
        recordTypes.put(fqcn, new PirType.RecordType(name, fields));
        forwardTypes.remove(fqcn);
        declared(name, fqcn);
    }

    /**
     * Register a sealed interface (ADR-068 D7, I11). A variant's tag is its position in {@code variants}; a variant
     * that is not a registered record has no fields.
     *
     * @param parameterized whether the interface declares type parameters
     */
    public void registerSealed(String name, String fqcn, List<SealedVariant> variants, boolean parameterized) {
        if (sumTypes.containsKey(fqcn)) {
            throw new CompilerException("Duplicate sealed interface: '" + fqcn + "'. "
                    + "A sealed interface with this name is already registered.");
        }
        var constructors = new ArrayList<PirType.Constructor>();
        for (var variant : variants) {
            var record = recordTypes.get(variant.fqcn());
            constructors.add(new PirType.Constructor(variant.name(), constructors.size(),
                    record != null ? record.fields() : List.of()));
        }
        var sumType = new PirType.SumType(name, constructors);
        sumTypes.put(fqcn, sumType);
        forwardTypes.remove(fqcn);
        if (parameterized) parameterizedSumTypes.add(fqcn);
        declared(name, fqcn);
        for (var variant : variants) {
            variantToSumType.put(variant.fqcn(), sumType);
            variantFamilies.computeIfAbsent(variant.fqcn(), k -> new ArrayList<>()).add(sumType);
            declared(variant.name(), variant.fqcn());
        }
    }

    /** Make a recursive declaration name resolvable before its fields are completed. */
    public void predeclareNamedType(
            String fqcn, String name, PirType.NamedKind kind) {
        if (recordTypes.containsKey(fqcn) || sumTypes.containsKey(fqcn)
                || newTypes.containsKey(fqcn) || forwardTypes.containsKey(fqcn)) {
            throw new CompilerException("Duplicate named type: '" + fqcn + "'");
        }
        forwardTypes.put(fqcn, new PirType.NamedTypeRef(fqcn, name, kind));
        declared(name, fqcn);
    }

    /** Remove unfinished forward identities after a failed SCC registration. */
    public void discardForwardTypes(Collection<String> fqcns) {
        for (String fqcn : fqcns) {
            var removed = forwardTypes.remove(fqcn);
            if (removed != null) undeclared(removed.name(), fqcn);
        }
    }

    /**
     * Register a @NewType record. On-chain, it resolves to the underlying type
     * and its constructor/of() are identity operations.
     */
    public void registerNewType(String name, String fqcn, PirType underlyingType) {
        newTypes.put(fqcn, underlyingType);
        declared(name, fqcn);
    }

    /**
     * The registered definition of exactly {@code fqcn} (ADR-068 I12): no import, no simple name, no alias. A ledger
     * hash type is its underlying bytes (I11); a permitted type that is not a record is its constructor's shape.
     */
    public Optional<PirType> lookupNominal(String fqcn) {
        if (LEDGER_HASH_FQCNS.contains(fqcn)) return Optional.of(new PirType.ByteStringType());
        if (newTypes.containsKey(fqcn)) return Optional.of(newTypes.get(fqcn));
        if (recordTypes.containsKey(fqcn)) return Optional.of(recordTypes.get(fqcn));
        if (sumTypes.containsKey(fqcn)) return Optional.of(sumTypes.get(fqcn));
        if (forwardTypes.containsKey(fqcn)) return Optional.of(forwardTypes.get(fqcn));
        var parent = variantToSumType.get(fqcn);
        if (parent == null) return Optional.empty();
        var variantName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        return parent.constructors().stream().filter(constructor -> constructor.name().equals(variantName)).findFirst()
                .map(constructor -> new PirType.RecordType(constructor.name(), constructor.fields()));
    }

    /** The "Unknown type" message for a name no registry holds, with a suggestion. */
    public String unknownTypeMessage(String name, String fqcn) {
        var suggestion = similarName(name, suggestions);
        return "Unknown type: " + name
                + (fqcn.equals(name) ? "" : " (resolved to " + fqcn + ")")
                + (suggestion != null ? ". Did you mean: " + suggestion + "?" : "");
    }

    /**
     * What a registry holds, by identity (ADR-068 I11): the frontends' registries are compared on this. The JavaParser
     * frontend's flat ledger aliases ({@link TypeResolver#addFlatVariantAliases}) are lookups, not declarations, so they
     * are not in it.
     *
     * @param records       record identity -> its type
     * @param sums          sealed interface identity -> its type
     * @param variants      variant identity -> the sealed interfaces it was registered under, in order
     * @param newTypes      {@code @NewType} identity -> its underlying type
     * @param parameterized the sealed interfaces that declare type parameters
     */
    public record Snapshot(Map<String, PirType.RecordType> records, Map<String, PirType.SumType> sums,
                           Map<String, List<PirType.SumType>> variants, Map<String, PirType> newTypes,
                           Set<String> parameterized) {}

    /** A snapshot of this registry. */
    public Snapshot snapshot() {
        var variants = new LinkedHashMap<String, List<PirType.SumType>>();
        variantFamilies.forEach((fqcn, sums) -> variants.put(fqcn, List.copyOf(sums)));
        return new Snapshot(Collections.unmodifiableMap(new LinkedHashMap<>(recordTypes)),
                Collections.unmodifiableMap(new LinkedHashMap<>(sumTypes)), Collections.unmodifiableMap(variants),
                Collections.unmodifiableMap(new LinkedHashMap<>(newTypes)), Set.copyOf(parameterizedSumTypes));
    }

    /**
     * Find a concrete sealed-interface variant used as an independent value in
     * a typed boundary graph. Constructor lowering encodes such a record with
     * its parent sum tag, while an ordinary record has tag zero. Until the type
     * model carries that nominal tag explicitly, accepting the concrete record
     * here would make construction, schema generation, and boundary checking
     * disagree. The sealed-interface type itself remains fully supported.
     */
    public Optional<String> findStandaloneVariantRecord(PirType type) {
        return findStandaloneVariantRecord(type,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private Optional<String> findStandaloneVariantRecord(
            PirType unresolved, Set<PirType> active) {
        if (unresolved instanceof PirType.NamedTypeRef ref) {
            var parent = variantToSumType.get(ref.stableId());
            if (parent != null) return Optional.of(variantDescription(ref.name(), parent));
            unresolved = resolveNamed(unresolved);
        }
        if (!active.add(unresolved)) return Optional.empty();
        try {
            if (unresolved instanceof PirType.RecordType record) {
                for (var entry : variantToSumType.entrySet()) {
                    if (recordTypes.get(entry.getKey()) == record) {
                        return Optional.of(variantDescription(record.name(), entry.getValue()));
                    }
                }
                for (var field : record.fields()) {
                    var found = findStandaloneVariantRecord(field.type(), active);
                    if (found.isPresent()) return found;
                }
            } else if (unresolved instanceof PirType.SumType sum) {
                for (var constructor : sum.constructors()) {
                    for (var field : constructor.fields()) {
                        var found = findStandaloneVariantRecord(field.type(), active);
                        if (found.isPresent()) return found;
                    }
                }
            } else if (unresolved instanceof PirType.ListType list) {
                return findStandaloneVariantRecord(list.elemType(), active);
            } else if (unresolved instanceof PirType.MapType map) {
                var key = findStandaloneVariantRecord(map.keyType(), active);
                return key.isPresent()
                        ? key
                        : findStandaloneVariantRecord(map.valueType(), active);
            } else if (unresolved instanceof PirType.OptionalType optional) {
                return findStandaloneVariantRecord(optional.elemType(), active);
            }
            return Optional.empty();
        } finally {
            active.remove(unresolved);
        }
    }

    private static String variantDescription(String variantName, PirType.SumType parent) {
        return "variant record '" + variantName + "' of sealed interface '"
                + parent.name() + "'";
    }

    /** Resolve a nominal recursive reference to its completed compiler definition. */
    public PirType resolveNamed(PirType type) {
        if (!(type instanceof PirType.NamedTypeRef ref)) return type;
        PirType resolved = switch (ref.kind()) {
            case RECORD -> recordTypes.get(ref.stableId());
            case SUM -> sumTypes.get(ref.stableId());
        };
        if (resolved == null) {
            throw new CompilerException(
                    "Recursive type definition is incomplete: '" + ref.stableId() + "'");
        }
        return resolved;
    }

    /** Return whether a type graph contains a PV11 native Value. */
    public boolean containsNativeOpaque(PirType type) {
        return containsNativeOpaque(type, new HashSet<>(),
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private boolean containsNativeOpaque(
            PirType type, Set<String> activeNames, Set<PirType> activeTypes) {
        if (PirType.isNativeOpaque(type)) return true;
        if (type instanceof PirType.NamedTypeRef ref) {
            if (!activeNames.add(ref.stableId())) return false;
            try {
                return containsNativeOpaque(resolveNamed(ref), activeNames, activeTypes);
            } finally {
                activeNames.remove(ref.stableId());
            }
        }
        if (!activeTypes.add(type)) return false;
        try {
            if (type instanceof PirType.ListType list) {
                return containsNativeOpaque(list.elemType(), activeNames, activeTypes);
            }
            if (type instanceof PirType.PairType pair) {
                return containsNativeOpaque(pair.first(), activeNames, activeTypes)
                        || containsNativeOpaque(pair.second(), activeNames, activeTypes);
            }
            if (type instanceof PirType.MapType map) {
                return containsNativeOpaque(map.keyType(), activeNames, activeTypes)
                        || containsNativeOpaque(map.valueType(), activeNames, activeTypes);
            }
            if (type instanceof PirType.OptionalType optional) {
                return containsNativeOpaque(optional.elemType(), activeNames, activeTypes);
            }
            if (type instanceof PirType.ArrayType array) {
                return containsNativeOpaque(array.elemType(), activeNames, activeTypes);
            }
            if (type instanceof PirType.FunType function) {
                return containsNativeOpaque(function.paramType(), activeNames, activeTypes)
                        || containsNativeOpaque(function.returnType(), activeNames, activeTypes);
            }
            if (type instanceof PirType.RecordType record) {
                for (var field : record.fields()) {
                    if (containsNativeOpaque(field.type(), activeNames, activeTypes)) return true;
                }
            }
            if (type instanceof PirType.SumType sum) {
                for (var constructor : sum.constructors()) {
                    for (var field : constructor.fields()) {
                        if (containsNativeOpaque(field.type(), activeNames, activeTypes)) return true;
                    }
                }
            }
            return false;
        } finally {
            activeTypes.remove(type);
        }
    }

    /** Snapshot of completed named definitions, keyed by stable compiler identity. */
    public Map<String, PirType> namedDefinitions() {
        var result = new LinkedHashMap<String, PirType>();
        result.putAll(recordTypes);
        result.putAll(sumTypes);
        return Collections.unmodifiableMap(result);
    }

    /** Return all registered FQCNs (for building knownFqcns sets). */
    public Set<String> allRegisteredFqcns() {
        var all = new LinkedHashSet<String>();
        all.addAll(recordTypes.keySet());
        all.addAll(sumTypes.keySet());
        all.addAll(variantToSumType.keySet());
        all.addAll(newTypes.keySet());
        all.addAll(forwardTypes.keySet());
        return all;
    }

    /**
     * The name of {@code candidates} or the built-in types closest to {@code name} (for "did you mean?" suggestions).
     * Uses case-insensitive Levenshtein distance.
     */
    static String similarName(String name, Collection<String> candidates) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        String lowerName = name.toLowerCase();

        for (String registered : candidates) {
            int dist = StringUtils.levenshtein(lowerName, registered.toLowerCase());
            if (dist < bestDist && dist <= 3) {
                bestDist = dist;
                best = registered;
            }
        }
        for (String builtin : BUILTIN_TYPE_NAMES) {
            int dist = StringUtils.levenshtein(lowerName, builtin.toLowerCase());
            if (dist < bestDist && dist <= 3) {
                bestDist = dist;
                best = builtin;
            }
        }
        return best;
    }
}
