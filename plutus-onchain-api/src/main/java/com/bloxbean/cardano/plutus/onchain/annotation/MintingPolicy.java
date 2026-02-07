package com.bloxbean.cardano.plutus.onchain.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Plutus minting policy.
 * <p>
 * The class must contain exactly one {@link Entrypoint} method.
 * Minting policies receive {@code (redeemer, scriptContext)} parameters.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MintingPolicy {
}
