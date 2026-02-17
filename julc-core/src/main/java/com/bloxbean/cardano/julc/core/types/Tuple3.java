package com.bloxbean.cardano.julc.core.types;

/**
 * A 3-element tuple with generic type parameters.
 * <p>
 * On-chain: compiled to {@code ConstrData(0, [first, second, third])}.
 * With type parameters, field access auto-unwraps: {@code Tuple3<BigInteger, byte[], BigInteger> t; t.first()}
 * returns an integer directly (no manual {@code Builtins.unIData()} needed).
 * <p>
 * Raw {@code Tuple3} (without type args) defaults to DataType fields for backward compatibility.
 */
public record Tuple3<A, B, C>(A first, B second, C third) {}
