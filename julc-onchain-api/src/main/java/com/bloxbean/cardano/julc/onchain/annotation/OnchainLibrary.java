package com.bloxbean.cardano.julc.onchain.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an on-chain library whose static methods can be called
 * from {@link Validator} or {@link MintingPolicy} classes.
 * <p>
 * Library classes must contain only {@code public static} methods that follow
 * the same Java subset as validators (no try/catch, no null, etc.).
 * <p>
 * When published in a JAR, the Gradle plugin bundles the library's Java source
 * under {@code META-INF/plutus-sources/} (preserving package structure) so that
 * consuming projects can automatically discover and compile the library to UPLC.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnchainLibrary {
}
