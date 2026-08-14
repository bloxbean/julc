package com.bloxbean.cardano.julc.verification.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the fixed authority and exact own-policy asset shape to verify. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface ControlledMint {
    String authority();
    String tokenName();
    long quantity();
    MintAction action();
}
