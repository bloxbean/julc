package com.bloxbean.cardano.julc.jrl.ast;

import java.util.List;

/**
 * Pattern match on a variant type, used in Redeemer(...) and Datum(...) fact patterns.
 * <p>
 * Examples:
 * <pre>
 *   Claim                          -- no fields
 *   Bid( bidder: $b, amount: $a )  -- with field bindings
 * </pre>
 */
public record VariantMatch(String typeName, List<FieldMatch> fields, SourceRange sourceRange) {}
