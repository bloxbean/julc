package org.julclang.compiler.backend;

import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * One provider materialization group (ADR-059). The group is closed as a unit and is linked
 * once, so private dependencies shared by several requested exports are not duplicated.
 * Bodies are trusted provider output: the {@link PirVerifier} checks producer references to
 * them only at the declared binding types.
 *
 * @param provider    provider identity for diagnostics
 * @param revision    the backend contract revision the provider implements
 * @param definitions closed group of strict definitions, in dependency-compatible order
 * @param bindings    the binding name and declared type of each requested implementation
 * @param namedTypes  named type definitions referenced by the binding types
 * @param types       provider type descriptions; their approved operations decide which
 *                    imported types a producer may match or construct (#183)
 */
public record LibraryImports(
        String provider,
        int revision,
        SequencedMap<String, PirTerm> definitions,
        Map<LibraryRequest, Binding> bindings,
        Map<String, PirType> namedTypes,
        Map<String, LibraryType> types) {

    /** The name a producer references and the type it may assume. */
    public record Binding(String name, PirType type) {
        public Binding {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    public LibraryImports {
        Objects.requireNonNull(provider, "provider");
        definitions = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(definitions));
        bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        namedTypes = Collections.unmodifiableMap(new LinkedHashMap<>(namedTypes));
        types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
        String subject = "library imports from " + provider;
        for (var entry : definitions.entrySet()) {
            if (entry.getKey().isBlank())
                throw new BackendException(DiagnosticCodes.BACKEND_INVALID_DESCRIPTOR, subject,
                        subject, "a definition name is blank");
            var free = new LinkedHashSet<>(PirClosure.freeVariables(entry.getValue()));
            free.removeAll(definitions.keySet());
            if (!free.isEmpty())
                throw new BackendException(DiagnosticCodes.BACKEND_INVALID_DESCRIPTOR, subject,
                        subject, "definition " + entry.getKey() + " is not closed over the group: "
                                + free);
        }
        for (var binding : bindings.entrySet())
            if (!definitions.containsKey(binding.getValue().name()))
                throw new BackendException(DiagnosticCodes.BACKEND_INVALID_DESCRIPTOR, subject,
                        subject, "binding for " + binding.getKey().describe()
                                + " names no definition: " + binding.getValue().name());
    }

    /** A group whose imported named types have no approved operations. */
    public LibraryImports(String provider, int revision, SequencedMap<String, PirTerm> definitions,
                          Map<LibraryRequest, Binding> bindings, Map<String, PirType> namedTypes) {
        this(provider, revision, definitions, bindings, namedTypes, Map.of());
    }

    /** The binding for a request materialized by this group. */
    public Binding binding(LibraryRequest request) {
        var binding = bindings.get(request);
        if (binding == null)
            throw new IllegalArgumentException(
                    "Request " + request.describe() + " was not materialized by " + provider);
        return binding;
    }
}
