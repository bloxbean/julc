package com.bloxbean.cardano.julc.stdlib.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Plutus minting policy validator.
 * <p>
 * The class must contain exactly one {@link Entrypoint} method.
 * Minting validators receive {@code (redeemer, scriptContext)} parameters.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MintingValidator {
}
