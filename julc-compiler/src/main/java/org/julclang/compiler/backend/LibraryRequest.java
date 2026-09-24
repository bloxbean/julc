package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirType;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** A request for a provider-owned, closed implementation (ADR-059). */
public sealed interface LibraryRequest permits LibraryRequest.Export, LibraryRequest.Instantiate,
        LibraryRequest.Operation, LibraryRequest.Codec {

    /** A stable description used in diagnostics. */
    String describe();

    /** A concrete public export identified by its qualified symbol. */
    record Export(String symbol) implements LibraryRequest {
        public Export {
            Objects.requireNonNull(symbol, "symbol");
            if (symbol.isBlank()) throw new IllegalArgumentException("Export symbol must not be blank");
        }

        @Override
        public String describe() {
            return symbol;
        }
    }

    /**
     * A generic export specialized at concrete type arguments (#181), in the order of the
     * scheme's type parameters. Arguments are source references without variables.
     */
    record Instantiate(String symbol, List<LibraryType.Reference> typeArguments) implements LibraryRequest {
        public Instantiate {
            Objects.requireNonNull(symbol, "symbol");
            typeArguments = List.copyOf(typeArguments);
        }

        @Override
        public String describe() {
            return symbol + "<" + String.join(",", typeArguments.stream()
                    .map(LibraryType.Reference::canonical).toList()) + ">";
        }
    }

    /**
     * An approved operation on an imported type (#183). {@code member} names the constructor
     * for {@link LibraryType.Operation#CONSTRUCT} on a sum and the field for
     * {@link LibraryType.Operation#PROJECT} on a record; it is null otherwise.
     */
    record Operation(String type, LibraryType.Operation operation, String member) implements LibraryRequest {
        public Operation {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operation, "operation");
        }

        public Operation(String type, LibraryType.Operation operation) {
            this(type, operation, null);
        }

        @Override
        public String describe() {
            return type + "#" + operation.name().toLowerCase(Locale.ROOT) + (member == null ? "" : "." + member);
        }
    }

    /**
     * An explicit conversion between a computational type and Plutus Data (#183), for
     * containers whose computational and serialized forms differ. Nominal leaves must be
     * provider types approving {@link LibraryType.Operation#ENCODE} or
     * {@link LibraryType.Operation#DECODE_STRICT}.
     */
    record Codec(PirType type, Direction direction) implements LibraryRequest {
        public enum Direction { ENCODE, DECODE_STRICT }

        public Codec {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(direction, "direction");
        }

        @Override
        public String describe() {
            return direction.name().toLowerCase(Locale.ROOT) + " " + PirVerifier.show(type);
        }
    }
}
