package com.bloxbean.cardano.julc.crl.ast;

/**
 * A field expression inside a datum constraint: {@code fieldName: expr}.
 */
public record DatumFieldExpr(String fieldName, Expression value) {}
