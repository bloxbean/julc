package com.bloxbean.cardano.julc.stdlib.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a single-field record as a newtype — a zero-cost type alias.
 * <p>
 * On-chain, the constructor and {@code .of()} factory method compile to identity
 * (no ConstrData wrapping). The underlying type must be a supported primitive:
 * {@code byte[]}, {@code BigInteger}, {@code String}, or {@code boolean}.
 * <p>
 * Example:
 * <pre>{@code
 * @NewType
 * record MyHash(byte[] hash) {}
 * // MyHash.of(bytes) — auto-registered, compiles to identity
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface NewType {
}
