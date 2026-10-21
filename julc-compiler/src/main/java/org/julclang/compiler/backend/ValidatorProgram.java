package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeSet;

/**
 * A revision-2 validator (ADR-059): one script whose ledger purpose selects exactly one
 * typed handler. Independent descriptors compile to independent scripts.
 *
 * @param revision             the backend contract revision the producer targets
 * @param requiredCapabilities capabilities the producer relies on, checked before any work
 * @param identity             the producer's stable validator identity, used in diagnostics and the ABI
 * @param target               the target used for producer specialization
 * @param boundary             the datum/redeemer boundary policy, {@code julc-strict-v1}
 * @param namedTypes           producer named type definitions
 * @param imports              provider materialization groups
 * @param definitions          producer definitions shared by the handlers, in evaluation order
 * @param parameters           deployment parameters in ABI order
 * @param handlers             purpose-indexed handlers; their order does not affect the script
 */
public record ValidatorProgram(
        int revision,
        Set<BackendCapability> requiredCapabilities,
        String identity,
        CompilerTarget target,
        String boundary,
        Map<String, PirType> namedTypes,
        List<LibraryImports> imports,
        SequencedMap<String, Definition> definitions,
        List<Parameter> parameters,
        List<Handler> handlers) {
    public ValidatorProgram {
        requiredCapabilities = Collections.unmodifiableSet(new TreeSet<>(requiredCapabilities));
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(boundary, "boundary");
        namedTypes = Collections.unmodifiableMap(new LinkedHashMap<>(namedTypes));
        imports = List.copyOf(imports);
        definitions = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(definitions));
        parameters = List.copyOf(parameters);
        handlers = List.copyOf(handlers);
    }

    /**
     * A typed handler for one ledger purpose. Its type is
     * {@code parameters -> [datum ->] redeemer -> context -> Bool}; only spending handlers
     * with a {@link DatumProfile#REQUIRED} or {@link DatumProfile#OPTIONAL} datum take a datum.
     *
     * @param purpose the ledger purpose that selects this handler
     * @param symbol  the producer's name for the handler, used in diagnostics and the ABI
     * @param term    the handler term; free variables must be imports or definitions
     * @param type    the declared handler type
     * @param datum   the datum profile; {@link DatumProfile#ABSENT} for non-spending purposes
     */
    public record Handler(ContractSchema.Purpose purpose, String symbol, PirTerm term, PirType type,
                          DatumProfile datum) {
        public Handler {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(term, "term");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(datum, "datum");
        }

        /** A spending handler that requires a datum. */
        public static Handler spending(String symbol, PirTerm term, PirType type) {
            return new Handler(ContractSchema.Purpose.SPEND, symbol, term, type, DatumProfile.REQUIRED);
        }

        /** A handler for a purpose without a datum argument. */
        public static Handler of(ContractSchema.Purpose purpose, String symbol, PirTerm term, PirType type) {
            return new Handler(purpose, symbol, term, type, DatumProfile.ABSENT);
        }
    }
}
