package com.bloxbean.cardano.julc.verification.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Requires the selected successor output to preserve the consumed state value. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface PreservesValue {
    OutputSelection output();
}
