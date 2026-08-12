package com.bloxbean.cardano.julc.verification.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires successful spending validation to imply that the selected datum
 * owner occurs in the transaction signatory list.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface RequiresSigner {
    /** A typed property path. Milestone C.5 supports {@code datum.<field>}. */
    String value();
}
