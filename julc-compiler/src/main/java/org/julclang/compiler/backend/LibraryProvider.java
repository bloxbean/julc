package org.julclang.compiler.backend;

import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Experimental neutral library surface: concrete exports with closed implementations. */
public interface LibraryProvider {
    List<LibraryExport> describe(String module);

    PirTerm materialize(String symbol);

    default Map<String, LibraryType> types() { return Map.of(); }

    default Map<String, PirType> namedDefinitions() {
        return Map.of();
    }

    /** Public exports that cannot currently be materialized, with actionable reasons. */
    default Map<String, String> unsupportedExports() { return Map.of(); }

    /** Generic exports of a module, described before specialization (#181). */
    default List<LibraryScheme> schemes(String module) {
        return List.of();
    }

    /** The backend contract revision this provider implements (ADR-059). */
    default int revision() {
        return BackendContract.REVISION_1;
    }

    /**
     * Materialize requests as one closed import group (ADR-059). This default adapts
     * {@link #materialize(String)}: each export becomes its own closed definition, so private
     * dependencies shared by several exports are duplicated. Providers that can link a shared
     * dependency closure override it.
     */
    default LibraryImports materialize(List<LibraryRequest> requests) {
        var definitions = new LinkedHashMap<String, PirTerm>();
        var bindings = new LinkedHashMap<LibraryRequest, LibraryImports.Binding>();
        for (var request : requests) {
            switch (request) {
                case LibraryRequest.Export export -> {
                    String symbol = export.symbol();
                    int dot = symbol.lastIndexOf('.');
                    var described = dot < 0 ? null : describe(symbol.substring(0, dot)).stream()
                            .filter(e -> e.symbol().equals(symbol)).findFirst().orElse(null);
                    if (described == null)
                        throw new BackendException(DiagnosticCodes.BACKEND_UNSUPPORTED_REQUEST,
                                symbol, symbol, "no concrete export with this symbol is described");
                    definitions.putIfAbsent(symbol, materialize(symbol));
                    bindings.put(request, new LibraryImports.Binding(symbol, described.type()));
                }
                case LibraryRequest.Instantiate instantiate -> throw new BackendException(
                        DiagnosticCodes.BACKEND_UNSUPPORTED_REQUEST, instantiate.describe(),
                        instantiate.describe(), "this provider describes no generic exports");
                case LibraryRequest.Operation operation -> bind(definitions, bindings, request,
                        new TypeOperations(types(), namedDefinitions()).operation(operation));
                case LibraryRequest.Codec codec -> bind(definitions, bindings, request,
                        new TypeOperations(types(), namedDefinitions()).codec(codec));
            }
        }
        return new LibraryImports(getClass().getName(), revision(), definitions, bindings,
                namedDefinitions(), types());
    }

    private static void bind(Map<String, PirTerm> definitions,
                             Map<LibraryRequest, LibraryImports.Binding> bindings,
                             LibraryRequest request, TypeOperations.Materialized materialized) {
        definitions.putIfAbsent(materialized.name(), materialized.term());
        bindings.put(request, new LibraryImports.Binding(materialized.name(), materialized.type()));
    }
}
