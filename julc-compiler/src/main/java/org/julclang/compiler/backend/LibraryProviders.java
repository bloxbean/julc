package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Composition of library providers (ADR-059, #182). */
public final class LibraryProviders {
    private LibraryProviders() {}

    /**
     * Compose providers with deterministic ownership. Every provider's revision is checked
     * first; a symbol, type or named definition described by more than one provider with
     * different meanings is rejected rather than resolved by order. Each request is routed to
     * its single owner and the owners' groups are merged into one import group, preserving
     * each provider's private dependency closure.
     */
    public static LibraryProvider compose(List<LibraryProvider> providers) {
        return new Composite(List.copyOf(providers));
    }

    private static final class Composite implements LibraryProvider {
        private final List<LibraryProvider> providers;
        private final Map<String, LibraryType> types = new LinkedHashMap<>();
        private final Map<String, PirType> namedDefinitions = new LinkedHashMap<>();
        private final Map<String, String> unsupported = new LinkedHashMap<>();

        Composite(List<LibraryProvider> providers) {
            if (providers.isEmpty()) throw invalid("composition", "at least one provider is required");
            this.providers = providers;
            for (var provider : providers) {
                String subject = "library provider " + provider.getClass().getName();
                if (provider.revision() < BackendContract.MINIMUM_REVISION
                        || provider.revision() > BackendContract.REVISION)
                    throw new BackendException(DiagnosticCodes.BACKEND_UNSUPPORTED_REVISION, subject, subject,
                            provider.revision(), BackendContract.MINIMUM_REVISION + ".." + BackendContract.REVISION);
                provider.types().forEach((identity, type) -> {
                    var previous = types.putIfAbsent(identity, type);
                    if (previous != null && !previous.equals(type))
                        throw invalid(subject, "type " + identity + " is described differently by another provider");
                });
                provider.namedDefinitions().forEach((identity, type) -> {
                    var previous = namedDefinitions.putIfAbsent(identity, type);
                    if (previous != null && !previous.equals(type))
                        throw invalid(subject, "named type " + identity + " is defined differently by another provider");
                });
                provider.unsupportedExports().forEach(unsupported::putIfAbsent);
            }
        }

        @Override
        public List<LibraryExport> describe(String module) {
            var exports = new ArrayList<LibraryExport>();
            var owners = new LinkedHashMap<String, LibraryProvider>();
            for (var provider : providers)
                for (var export : provider.describe(module)) {
                    claim(owners, export.symbol(), provider);
                    exports.add(export);
                }
            for (var provider : providers)
                for (var scheme : provider.schemes(module)) claim(owners, scheme.symbol(), provider);
            return List.copyOf(exports);
        }

        @Override
        public List<LibraryScheme> schemes(String module) {
            describe(module);
            var schemes = new ArrayList<LibraryScheme>();
            for (var provider : providers) schemes.addAll(provider.schemes(module));
            return List.copyOf(schemes);
        }

        @Override
        public PirTerm materialize(String symbol) {
            return owner(new LibraryRequest.Export(symbol)).materialize(symbol);
        }

        @Override public Map<String, LibraryType> types() { return Collections.unmodifiableMap(types); }
        @Override public Map<String, PirType> namedDefinitions() { return Collections.unmodifiableMap(namedDefinitions); }
        @Override public Map<String, String> unsupportedExports() { return Collections.unmodifiableMap(unsupported); }
        @Override public int revision() { return BackendContract.REVISION_2; }

        @Override
        public LibraryImports materialize(List<LibraryRequest> requests) {
            var byOwner = new LinkedHashMap<LibraryProvider, List<LibraryRequest>>();
            for (var provider : providers) byOwner.put(provider, new ArrayList<>());
            for (var request : requests) byOwner.get(owner(request)).add(request);
            var definitions = new LinkedHashMap<String, PirTerm>();
            var bindings = new LinkedHashMap<LibraryRequest, LibraryImports.Binding>();
            CompilerTarget target = null;
            for (var entry : byOwner.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                var group = entry.getKey().materialize(entry.getValue());
                if (group.target() != null) {
                    if (target != null && !target.equals(group.target()))
                        throw invalid("composition", "providers materialized for different targets: "
                                + target.profileId() + " and " + group.target().profileId());
                    target = group.target();
                }
                group.definitions().forEach((name, term) -> {
                    var previous = definitions.putIfAbsent(name, term);
                    if (previous != null && !previous.equals(term))
                        throw invalid("composition", "definition " + name
                                + " is materialized differently by two providers");
                });
                bindings.putAll(group.bindings());
            }
            return new LibraryImports("composite" + providers.stream()
                    .map(p -> p.getClass().getSimpleName()).toList(), BackendContract.REVISION_2, target,
                    definitions, bindings, namedDefinitions, types);
        }

        /** The single provider that owns a request; duplicates are rejected, not ordered. */
        private LibraryProvider owner(LibraryRequest request) {
            return switch (request) {
                case LibraryRequest.Export export -> uniqueOwner(request, export.symbol(), provider ->
                        provider.describe(module(export.symbol())).stream()
                                .anyMatch(e -> e.symbol().equals(export.symbol())));
                case LibraryRequest.Instantiate instantiate -> uniqueOwner(request, instantiate.symbol(), provider ->
                        provider.schemes(module(instantiate.symbol())).stream()
                                .anyMatch(s -> s.symbol().equals(instantiate.symbol())));
                case LibraryRequest.Operation operation -> providers.stream()
                        .filter(p -> p.types().containsKey(operation.type())).findFirst()
                        .orElseThrow(() -> unowned(request));
                case LibraryRequest.Codec codec -> providers.stream()
                        .filter(p -> ownsLeaves(p, codec.type())).findFirst()
                        .orElse(providers.getFirst());
            };
        }

        private LibraryProvider uniqueOwner(LibraryRequest request, String symbol,
                                            java.util.function.Predicate<LibraryProvider> owns) {
            var owners = providers.stream().filter(owns).toList();
            if (owners.isEmpty()) throw unowned(request);
            if (owners.size() > 1)
                throw invalid("composition", "symbol " + symbol + " is exported by more than one provider");
            return owners.getFirst();
        }

        private static boolean ownsLeaves(LibraryProvider provider, PirType type) {
            return switch (type) {
                case PirType.NamedTypeRef ref -> provider.types().containsKey(ref.stableId());
                case PirType.RecordType _, PirType.SumType _ -> provider.types().values().stream()
                        .anyMatch(t -> t.representation().equals(type));
                case PirType.ListType list -> ownsLeaves(provider, list.elemType());
                case PirType.OptionalType optional -> ownsLeaves(provider, optional.elemType());
                case PirType.MapType map -> ownsLeaves(provider, map.keyType()) && ownsLeaves(provider, map.valueType());
                default -> true;
            };
        }

        private void claim(Map<String, LibraryProvider> owners, String symbol, LibraryProvider provider) {
            var previous = owners.putIfAbsent(symbol, provider);
            if (previous != null && previous != provider)
                throw invalid("composition", "symbol " + symbol + " is exported by more than one provider");
        }

        private static String module(String symbol) {
            int dot = symbol.lastIndexOf('.');
            return dot < 0 ? "" : symbol.substring(0, dot);
        }

        private static BackendException unowned(LibraryRequest request) {
            return new BackendException(DiagnosticCodes.BACKEND_UNSUPPORTED_REQUEST, request.describe(),
                    request.describe(), "no composed provider owns this request");
        }
    }

    private static BackendException invalid(String subject, String detail) {
        Objects.requireNonNull(subject, "subject");
        return new BackendException(DiagnosticCodes.BACKEND_INVALID_DESCRIPTOR, subject, subject, detail);
    }
}
