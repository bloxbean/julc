package com.bloxbean.cardano.plutus.onchain.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the validator entrypoint.
 * <p>
 * The method must be {@code static} and return {@code boolean}.
 * There must be exactly one entrypoint per validator class.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Entrypoint {
}
