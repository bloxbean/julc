package com.bloxbean.cardano.julc.stdlib.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Plutus proposing validator.
 * <p>
 * The class must contain exactly one {@link Entrypoint} method.
 * Proposing validators receive {@code (redeemer, scriptContext)} parameters
 * and validate governance proposals.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProposingValidator {
}
