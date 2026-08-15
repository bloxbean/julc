package com.bloxbean.cardano.julc.compiler.schema;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.core.source.SourceLocation;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Compiler-owned description of a validator's on-chain interfaces.
 *
 * <p>This model records the already-resolved compiler types. It deliberately
 * contains no CIP-57, JSON, or Lean-specific representation.</p>
 */
public record ContractSchema(
        List<ValidatorInterface> interfaces,
        List<Argument> parameters,
        Map<String, PirType> namedDefinitions,
        boolean purposeIndexed) {

    public ContractSchema {
        interfaces = List.copyOf(interfaces);
        if (interfaces.isEmpty()) {
            throw new IllegalArgumentException("A contract schema must contain at least one interface");
        }
        var purposes = new HashSet<Purpose>();
        for (var validatorInterface : interfaces) {
            if (!purposes.add(validatorInterface.purpose())) {
                throw new IllegalArgumentException(
                        "Duplicate contract interface purpose: " + validatorInterface.purpose());
            }
        }
        parameters = List.copyOf(parameters);
        namedDefinitions = Collections.unmodifiableMap(
                new LinkedHashMap<>(namedDefinitions));
    }

    /** Convenience constructor for a normal, single-purpose validator. */
    public ContractSchema(
            Purpose purpose,
            Argument datum,
            Argument redeemer,
            List<Argument> parameters,
            Map<String, PirType> namedDefinitions) {
        this(List.of(new ValidatorInterface("validate", purpose, datum, redeemer, null)),
                parameters, namedDefinitions, false);
    }

    /** Convenience constructor for an acyclic single-purpose schema. */
    public ContractSchema(
            Purpose purpose,
            Argument datum,
            Argument redeemer,
            List<Argument> parameters) {
        this(purpose, datum, redeemer, parameters, Map.of());
    }

    /**
     * Return the sole interface, rejecting accidental use of a multi-purpose
     * schema where the caller has not selected a purpose.
     */
    public ValidatorInterface singleInterface() {
        if (interfaces.size() != 1) {
            throw new IllegalStateException(
                    "Contract schema has " + interfaces.size()
                            + " interfaces; select one by purpose");
        }
        return interfaces.getFirst();
    }

    /** Select exactly one interface for a purpose. */
    public Optional<ValidatorInterface> interfaceFor(Purpose purpose) {
        Objects.requireNonNull(purpose, "purpose");
        return interfaces.stream().filter(candidate -> candidate.purpose() == purpose).findFirst();
    }

    /** Create a single-interface view while retaining shared parameters and definitions. */
    public ContractSchema select(Purpose purpose) {
        var selected = interfaceFor(purpose).orElseThrow(() -> new IllegalArgumentException(
                "Contract schema has no " + purpose + " interface"));
        return new ContractSchema(List.of(selected), parameters, namedDefinitions, purposeIndexed);
    }

    /** Compatibility accessors for consumers that only support one interface. */
    public Purpose purpose() {
        return singleInterface().purpose();
    }

    public Argument datum() {
        return singleInterface().datum();
    }

    public Argument redeemer() {
        return singleInterface().redeemer();
    }

    /** JuLC ledger purposes, independent of any artifact-format vocabulary. */
    public enum Purpose {
        MINT,
        SPEND,
        WITHDRAW,
        CERTIFY,
        VOTE,
        PROPOSE
    }

    /** One purpose-specific on-chain interface exposed by a compiled script. */
    public record ValidatorInterface(
            String entrypointName,
            Purpose purpose,
            Argument datum,
            Argument redeemer,
            SourceLocation sourceLocation) {
        public ValidatorInterface {
            entrypointName = Objects.requireNonNull(entrypointName, "entrypointName");
            purpose = Objects.requireNonNull(purpose, "purpose");
            redeemer = Objects.requireNonNull(redeemer, "redeemer");
        }
    }

    /** A named contract boundary value and the type selected by the compiler. */
    public record Argument(String name, PirType type, SourceLocation sourceLocation) {
        public Argument {
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type");
        }
    }
}
