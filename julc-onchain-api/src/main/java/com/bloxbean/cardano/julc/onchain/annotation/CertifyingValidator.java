package com.bloxbean.cardano.julc.onchain.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Plutus certifying validator.
 * <p>
 * The class must contain exactly one {@link Entrypoint} method.
 * Certifying validators receive {@code (redeemer, scriptContext)} parameters
 * and validate delegation certificate operations.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CertifyingValidator {
}
