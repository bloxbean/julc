package com.bloxbean.cardano.julc.crl.ast;

import java.util.List;

/**
 * Datum constraint in an Output fact pattern: {@code inline TypeName( field: expr, ... )}.
 */
public record DatumConstraint(String typeName, List<DatumFieldExpr> fields) {}
