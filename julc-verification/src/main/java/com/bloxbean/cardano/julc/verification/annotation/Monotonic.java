package com.bloxbean.cardano.julc.verification.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a typed monotonic state transition for formal verification. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Monotonic {
    String current();
    String next();
    Relation relation();
}
