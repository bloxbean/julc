package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.schema.ContractSchema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Neutral metadata for a compiled {@link ValidatorProgram} (ADR-059): enough for a consumer
 * to apply parameters in order and record each handler's role and boundary types.
 *
 * @param identity   the producer's validator identity
 * @param target     the compiler target
 * @param boundary   the boundary policy
 * @param parameters deployment parameters in the order {@code Program.applyParams} applies them
 * @param handlers   handlers in ledger-tag order
 * @param namedTypes the named type definitions referenced by parameter and boundary types
 */
public record ValidatorAbi(
        String identity,
        CompilerTarget target,
        String boundary,
        List<Parameter> parameters,
        List<HandlerAbi> handlers,
        Map<String, PirType> namedTypes) {
    public ValidatorAbi {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(boundary, "boundary");
        parameters = List.copyOf(parameters);
        handlers = List.copyOf(handlers);
        namedTypes = Collections.unmodifiableMap(new LinkedHashMap<>(namedTypes));
    }

    /**
     * One handler's role.
     *
     * @param purpose      the ledger purpose
     * @param tag          the ScriptInfo constructor tag that selects it
     * @param symbol       the producer's handler name
     * @param datum        the datum profile
     * @param datumType    the datum type the handler receives, or null without a datum argument
     * @param redeemerType the redeemer type
     */
    public record HandlerAbi(ContractSchema.Purpose purpose, int tag, String symbol,
                             DatumProfile datum, PirType datumType, PirType redeemerType) {
        public HandlerAbi {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(datum, "datum");
            Objects.requireNonNull(redeemerType, "redeemerType");
        }
    }
}
