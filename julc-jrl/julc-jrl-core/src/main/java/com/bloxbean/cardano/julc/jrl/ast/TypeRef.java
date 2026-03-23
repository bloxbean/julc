package com.bloxbean.cardano.julc.jrl.ast;

/**
 * JRL type reference. Represents type annotations in params, datum fields, redeemer fields, etc.
 */
public sealed interface TypeRef {

    SourceRange sourceRange();

    record SimpleType(String name, SourceRange sourceRange) implements TypeRef {}
    record ListType(TypeRef elementType, SourceRange sourceRange) implements TypeRef {}
    record OptionalType(TypeRef elementType, SourceRange sourceRange) implements TypeRef {}
}
