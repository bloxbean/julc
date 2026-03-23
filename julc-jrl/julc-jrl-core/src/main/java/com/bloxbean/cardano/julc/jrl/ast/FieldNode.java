package com.bloxbean.cardano.julc.jrl.ast;

/**
 * A named, typed field in a datum, record, redeemer, or variant declaration.
 */
public record FieldNode(String name, TypeRef type, SourceRange sourceRange) {}
