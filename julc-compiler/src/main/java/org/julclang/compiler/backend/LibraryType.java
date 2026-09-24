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

    /**
     * A source type reference. A {@linkplain #variable(String) variable} names a type parameter
     * of a {@link LibraryScheme}; it is never a nominal type.
     */
    public record Reference(String name, List<Reference> arguments, boolean variable) {
        public Reference {
            arguments = List.copyOf(arguments);
            if (variable && !arguments.isEmpty())
                throw new IllegalArgumentException("A type variable takes no arguments: " + name);
        }

        public Reference(String name, List<Reference> arguments) {
            this(name, arguments, false);
        }

        /** A scheme type variable. */
        public static Reference variable(String name) {
            return new Reference(name, List.of(), true);
        }

        /** Replace scheme variables with their bindings; unbound variables are an error. */
        public Reference substitute(java.util.Map<String, Reference> bindings) {
            if (variable) {
                var bound = bindings.get(name);
                if (bound == null) throw new IllegalArgumentException("Unbound type variable " + name);
                return bound;
            }
            return new Reference(name, arguments.stream().map(a -> a.substitute(bindings)).toList(), false);
        }

        /** Whether this reference mentions a type variable. */
        public boolean isGeneric() {
            return variable || arguments.stream().anyMatch(Reference::isGeneric);
        }

        /** A canonical text form, stable across runs, used in identities and keys. */
        public String canonical() {
            if (variable) return "'" + name;
            if (arguments.isEmpty()) return name;
            return name + "[" + String.join(",", arguments.stream().map(Reference::canonical).toList()) + "]";
        }
    }

    public record Field(String name, Reference type) {}
}
