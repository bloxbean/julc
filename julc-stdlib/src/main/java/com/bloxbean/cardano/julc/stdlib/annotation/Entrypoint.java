package com.bloxbean.cardano.julc.stdlib.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the validator entrypoint.
 * <p>
 * The method must be {@code static} and return {@code boolean}.
 * For single-purpose validators, there must be exactly one entrypoint per class.
 * <p>
 * For {@link MultiValidator} classes, the {@link #purpose()} attribute controls dispatch:
 * <ul>
 *   <li>{@code Purpose.DEFAULT} (default) — single entrypoint, user handles ScriptInfo dispatch</li>
 *   <li>Explicit purpose (e.g. {@code Purpose.MINT}) — compiler auto-dispatches by ScriptInfo tag</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Entrypoint {
    Purpose purpose() default Purpose.DEFAULT;
}
