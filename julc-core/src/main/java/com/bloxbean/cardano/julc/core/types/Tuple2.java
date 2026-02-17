package com.bloxbean.cardano.julc.core.types;

/**
 * A 2-element tuple with generic type parameters.
 * <p>
 * On-chain: compiled to {@code ConstrData(0, [first, second])}.
 * With type parameters, field access auto-unwraps: {@code Tuple2<BigInteger, byte[]> t; t.first()}
 * returns an integer directly (no manual {@code Builtins.unIData()} needed).
 * <p>
 * Raw {@code Tuple2} (without type args) defaults to DataType fields for backward compatibility.
 */
public record Tuple2<A, B>(A first, B second) {}
