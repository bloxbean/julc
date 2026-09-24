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
    private final List<PirType> unmatchable;
    private final List<PirType> unconstructible;
    private final List<PirType> uncheckable;
    private final Map<String, PirType> environment;
    private final SequencedMap<String, PirTerm> linkable;
    private final SequencedMap<String, Definition> definitions;

    private ProgramUnit(String subject, Map<String, PirType> namedTypes, List<PirType> unmatchable,
                        List<PirType> unconstructible, List<PirType> uncheckable,
                        Map<String, PirType> environment, SequencedMap<String, PirTerm> linkable,
                        SequencedMap<String, Definition> definitions) {
        this.subject = subject;
        this.namedTypes = namedTypes;
        this.unmatchable = unmatchable;
        this.unconstructible = unconstructible;
        this.uncheckable = uncheckable;
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
        var unmatchable = new ArrayList<PirType>();
        var unconstructible = new ArrayList<PirType>();
        var uncheckable = new ArrayList<PirType>();
        var environment = new LinkedHashMap<String, PirType>();
        var linkable = new LinkedHashMap<String, PirTerm>();
        for (var group : imports) {
            String groupSubject = subject + " import from " + group.provider();
            capabilities.requireRevision(group.revision(), groupSubject);
            group.namedTypes().forEach((id, definition) -> {
                var previous = namedTypes.putIfAbsent(id, definition);
                if (previous != null && !previous.equals(definition))
                    throw invalid(subject, "named type " + id + " has conflicting definitions");
                // Imported types are opaque unless their description approves the operation.
                var description = group.types().get(id);
                if (description == null || !description.supports(LibraryType.Operation.MATCH))
                    addOnce(unmatchable, definition);
                if (description == null || !description.supports(LibraryType.Operation.CONSTRUCT))
                    addOnce(unconstructible, definition);
                if (description == null || !description.supports(LibraryType.Operation.BOUNDARY))
                    addOnce(uncheckable, definition);
            });
            group.definitions().forEach((name, term) -> {
                if (name.startsWith(".") || name.startsWith("__") || name.startsWith("$julc$"))
                    throw invalid(groupSubject, "import definition name '" + name + "' is reserved");
                // Identical closed terms from several groups (the same export or operation
                // requested twice) are linked once; differing ones conflict.
                var previous = linkable.putIfAbsent(name, term);
                if (previous != null && !previous.equals(term))
                    throw invalid(subject, "import definition " + name
                            + " is supplied by more than one group with different terms");
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
                List.copyOf(unmatchable), List.copyOf(unconstructible), List.copyOf(uncheckable),
                Collections.unmodifiableMap(environment), linkable, definitions);
    }

    private static void addOnce(List<PirType> types, PirType type) {
        if (!types.contains(type)) types.add(type);
    }

    /** A verifier for this unit's named types and the imported types it may not match or build. */
    PirVerifier verifier(boolean builtinCase) {
        return new PirVerifier(namedTypes, unmatchable, unconstructible, builtinCase);
    }

    /** Imported types whose descriptions do not approve use at a strict boundary. */
    List<PirType> uncheckable() {
        return uncheckable;
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
