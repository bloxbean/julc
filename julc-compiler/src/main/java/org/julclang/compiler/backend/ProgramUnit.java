package org.julclang.compiler.backend;

import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * The program-level part shared by revision-2 descriptors (ADR-059): merged named types,
 * trusted import groups and producer definitions, their verification environment and
 * linking. Import groups are linked before producer definitions in one {@link PirLinker}
 * pass, so every dependency is bound before its first use and evaluated exactly once.
 */
final class ProgramUnit {
    private final String subject;
    private final Map<String, PirType> namedTypes;
    private final List<PirType> opaqueTypes;
    private final Map<String, PirType> environment;
    private final SequencedMap<String, PirTerm> linkable;
    private final SequencedMap<String, Definition> definitions;

    private ProgramUnit(String subject, Map<String, PirType> namedTypes, List<PirType> opaqueTypes,
                        Map<String, PirType> environment, SequencedMap<String, PirTerm> linkable,
                        SequencedMap<String, Definition> definitions) {
        this.subject = subject;
        this.namedTypes = namedTypes;
        this.opaqueTypes = opaqueTypes;
        this.environment = environment;
        this.linkable = linkable;
        this.definitions = definitions;
    }

    static ProgramUnit prepare(
            String subject,
            BackendCapabilities capabilities,
            Map<String, PirType> programTypes,
            List<LibraryImports> imports,
            SequencedMap<String, Definition> definitions) {
        var namedTypes = new LinkedHashMap<>(programTypes);
        var opaqueTypes = new ArrayList<PirType>();
        var environment = new LinkedHashMap<String, PirType>();
        var linkable = new LinkedHashMap<String, PirTerm>();
        for (var group : imports) {
            String groupSubject = subject + " import from " + group.provider();
            capabilities.requireRevision(group.revision(), groupSubject);
            group.namedTypes().forEach((id, definition) -> {
                var previous = namedTypes.putIfAbsent(id, definition);
                if (previous != null && !previous.equals(definition))
                    throw invalid(subject, "named type " + id + " has conflicting definitions");
                if (!opaqueTypes.contains(definition)) opaqueTypes.add(definition);
            });
            group.definitions().forEach((name, term) -> {
                if (name.startsWith(".") || name.startsWith("__") || name.startsWith("$julc$"))
                    throw invalid(groupSubject, "import definition name '" + name + "' is reserved");
                if (linkable.putIfAbsent(name, term) != null)
                    throw invalid(subject, "import definition " + name
                            + " is supplied by more than one group");
            });
            for (var binding : group.bindings().values()) environment.put(binding.name(), binding.type());
        }
        for (var entry : definitions.entrySet()) {
            String name = entry.getKey();
            if (PirVerifier.isReservedName(name))
                throw new BackendException(DiagnosticCodes.PIR_INVALID_BINDING, subject, subject,
                        "definition name '" + name + "' is reserved or blank");
            if (linkable.containsKey(name))
                throw new BackendException(DiagnosticCodes.PIR_INVALID_BINDING, subject, subject,
                        "definition " + name + " conflicts with an imported definition");
            linkable.put(name, entry.getValue().term());
            environment.put(name, entry.getValue().type());
        }
        return new ProgramUnit(subject, Collections.unmodifiableMap(namedTypes),
                List.copyOf(opaqueTypes), Collections.unmodifiableMap(environment), linkable,
                definitions);
    }

    /** A verifier for this unit's named types and imported opaque types. */
    PirVerifier verifier(boolean builtinCase) {
        return new PirVerifier(namedTypes, opaqueTypes, builtinCase);
    }

    /** Verify every producer definition against its declared type. */
    void verifyDefinitions(PirVerifier verifier) {
        definitions.forEach((name, definition) ->
                verifier.verify(name, definition.term(), definition.type(), environment));
    }

    /** Whether a program-level name is already taken by an import or definition. */
    boolean defines(String name) {
        return linkable.containsKey(name);
    }

    Map<String, PirType> environment() {
        return environment;
    }

    Map<String, PirType> namedTypes() {
        return namedTypes;
    }

    /**
     * Link imports, definitions and backend-owned bindings around {@code root}. Extra
     * bindings are linked after producer definitions, in the given order.
     */
    PirTerm link(SequencedMap<String, PirTerm> extra, PirTerm root) {
        var all = new LinkedHashMap<>(linkable);
        extra.forEach((name, term) -> {
            if (all.putIfAbsent(name, term) != null)
                throw invalid(subject, "backend binding " + name + " conflicts with a program name");
        });
        try {
            return PirLinker.link(all, root);
        } catch (BackendException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new BackendException(DiagnosticCodes.PIR_UNSUPPORTED_STRUCTURE, subject,
                    subject, e.getMessage());
        }
    }

    private static BackendException invalid(String subject, String detail) {
        return new BackendException(DiagnosticCodes.BACKEND_INVALID_DESCRIPTOR, subject, subject, detail);
    }
}
