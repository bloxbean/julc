package org.julclang.compiler.backend;

import org.julclang.compiler.CompilerTarget;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A generic library export described before specialization (ADR-059, #181). The scheme is
 * source and export metadata, not a PIR type: a consumer instantiates it with concrete type
 * arguments through {@link LibraryRequest.Instantiate} and receives typed, closed PIR.
 *
 * @param identity       a stable export identity, independent of source order
 * @param symbol         the qualified export symbol
 * @param typeParameters the type parameters in declaration order
 * @param parameters     the source parameter types in order; they may mention the parameters
 * @param result         the source result type
 * @param target         the compiler target the provider specializes for
 */
public record LibraryScheme(
        String identity,
        String symbol,
        List<TypeParameter> typeParameters,
        List<LibraryType.Reference> parameters,
        LibraryType.Reference result,
        CompilerTarget target) {
    public LibraryScheme {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(symbol, "symbol");
        typeParameters = List.copyOf(typeParameters);
        parameters = List.copyOf(parameters);
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(target, "target");
    }

    /** Source arity. */
    public int arity() {
        return parameters.size();
    }

    /** A type parameter and the constraints every substitution must satisfy. */
    public record TypeParameter(String name, Set<Constraint> constraints) {
        public TypeParameter {
            Objects.requireNonNull(name, "name");
            constraints = constraints.isEmpty()
                    ? Collections.unmodifiableSet(EnumSet.noneOf(Constraint.class))
                    : Collections.unmodifiableSet(EnumSet.copyOf(constraints));
        }
    }

    /** Explicit, supported substitution constraints. */
    public enum Constraint {
        /**
         * The substitution has a Plutus Data encoding: an integer, bytes, string, Bool, Data,
         * a constructor-encoded record, sum or newtype, or a list, map or optional of such types.
         * Generic Julc code stores values of a type parameter as Data-encoded list elements.
         */
        DATA_ENCODABLE
    }
}
