package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Nominal source identity is retained even for zero-cost Java @NewType representations.
 * {@link #operations} lists the operations whose representation the provider has verified
 * (ADR-059); a consumer must not infer others from fields, constructors or the representation.
 */
public record LibraryType(
        String identity, PirType representation, boolean newType, List<Field> fields,
        List<Constructor> constructors, Set<Operation> operations) {

    /** The revision of the representation and operation contract described here. */
    public static final int REPRESENTATION_REVISION = 1;

    /** Operations a provider may approve for an imported type. */
    public enum Operation {
        /** Build values from typed fields ({@code DataConstr}, or the identity for newtypes). */
        CONSTRUCT,
        /** Read typed fields of a constructor-encoded record, or a newtype's underlying value. */
        PROJECT,
        /** Match constructors with {@code DataMatch} in declared tag order. */
        MATCH,
        /** Equality with the Java {@code equals} semantics of the representation. */
        EQUALS,
        /** Encode a value as Plutus Data. */
        ENCODE,
        /** Decode Plutus Data with the strict boundary check; malformed Data fails. */
        DECODE_STRICT,
        /** Use as a validator datum, redeemer or parameter under the strict boundary policy. */
        BOUNDARY
    }

    public LibraryType {
        fields = List.copyOf(fields);
        constructors = List.copyOf(constructors);
        operations = operations.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(Operation.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(operations));
    }

    /** A description without approved operations. */
    public LibraryType(String identity, PirType representation, boolean newType, List<Field> fields,
                       List<Constructor> constructors) {
        this(identity, representation, newType, fields, constructors, Set.of());
    }

    public LibraryType(String identity, PirType representation, boolean newType, List<Field> fields) {
        this(identity, representation, newType, fields, List.of());
    }

    public boolean supports(Operation operation) {
        return operations.contains(operation);
    }

    public record Constructor(String name, int tag, List<Field> fields) {
        public Constructor { fields = List.copyOf(fields); }
    }

    public record Reference(String name, List<Reference> arguments) {
        public Reference {
            arguments = List.copyOf(arguments);
        }
    }

    public record Field(String name, Reference type) {}
}
