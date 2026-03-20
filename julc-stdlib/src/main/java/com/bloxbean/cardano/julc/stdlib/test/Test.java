package com.bloxbean.cardano.julc.stdlib.test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Marks a static boolean method as a julcc test.
 * Tests are compiled and evaluated on the UPLC VM.
 * Return true to pass, false to fail.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Test {
}
