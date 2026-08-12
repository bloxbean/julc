package com.bloxbean.cardano.julc.compiler.schema;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.core.source.SourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * Compiler-owned description of a validator's on-chain interface.
 *
 * <p>This model records the already-resolved compiler types. It deliberately
 * contains no CIP-57, JSON, or Lean-specific representation.</p>
 */
public record ContractSchema(
        String purpose,
        Argument datum,
        Argument redeemer,
        List<Argument> parameters) {

    public ContractSchema {
        purpose = Objects.requireNonNull(purpose, "purpose");
        redeemer = Objects.requireNonNull(redeemer, "redeemer");
        parameters = List.copyOf(parameters);
    }

    /** A named contract boundary value and the type selected by the compiler. */
    public record Argument(String name, PirType type, SourceLocation sourceLocation) {
        public Argument {
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type");
        }
    }
}
