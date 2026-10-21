package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeSet;

/**
 * A revision-2 function program (ADR-059): trusted provider imports, verified producer
 * definitions and one entry term, compiled without a validator wrapper.
 *
 * @param revision             the backend contract revision the producer targets
 * @param requiredCapabilities capabilities the producer relies on, checked before any work
 * @param target               the target used for producer specialization
 * @param namedTypes           producer named type definitions
 * @param imports              provider materialization groups, linked before definitions
 * @param definitions          producer definitions in strict evaluation order
 * @param symbol               the producer's name for the entry, used in diagnostics
 * @param term                 the entry term; free variables must be imports or definitions
 * @param type                 the declared entry type
 */
public record FunctionProgram(
        int revision,
        Set<BackendCapability> requiredCapabilities,
        CompilerTarget target,
        Map<String, PirType> namedTypes,
        List<LibraryImports> imports,
        SequencedMap<String, Definition> definitions,
        String symbol,
        PirTerm term,
        PirType type) {
    public FunctionProgram {
        requiredCapabilities = Collections.unmodifiableSet(new TreeSet<>(requiredCapabilities));
        Objects.requireNonNull(target, "target");
        namedTypes = Collections.unmodifiableMap(new LinkedHashMap<>(namedTypes));
        imports = List.copyOf(imports);
        definitions = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(definitions));
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(type, "type");
    }
}
