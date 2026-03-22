package com.bloxbean.cardano.julc.crl.ast;

import java.util.List;

/**
 * The right-hand side of a field match in a variant pattern.
 */
public sealed interface MatchValue {

    /** Variable binding: {@code $varName} */
    record Binding(String varName, SourceRange sourceRange) implements MatchValue {}

    /** Literal or expression match: {@code 42}, {@code param_name} */
    record Literal(Expression expr, SourceRange sourceRange) implements MatchValue {}

    /** Nested record destructuring: {@code NestedType( field: $var )} */
    record NestedMatch(String typeName, List<FieldMatch> fields,
                       SourceRange sourceRange) implements MatchValue {}
}
