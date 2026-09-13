package org.julclang.jrl.ast;

/**
 * A field expression inside a datum constraint: {@code fieldName: expr}.
 */
public record DatumFieldExpr(String fieldName, Expression value) {}
