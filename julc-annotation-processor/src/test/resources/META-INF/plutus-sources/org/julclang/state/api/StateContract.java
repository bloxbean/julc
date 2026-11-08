package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stand-in for JOUST's contract annotation, shipped to JuLC as a library source the way JOUST's API jar ships it
 * (ADR-071 tests). It does not carry {@code @OnchainLibrary}: JuLC treats it as an alias of it.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface StateContract {
    long minAda() default 0;
    int maxDatumBytes() default 512;
    long maxValidityMs() default 3_600_000;
    int assetSlots() default -1;
    boolean stakeSelf() default false;
}
