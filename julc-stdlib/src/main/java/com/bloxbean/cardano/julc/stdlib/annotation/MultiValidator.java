package com.bloxbean.cardano.julc.stdlib.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a multi-purpose Plutus validator.
 * <p>
 * A multi-validator can handle multiple script purposes (mint, spend, withdraw, etc.)
 * within a single compiled script. This supports two modes:
 * <ul>
 *   <li><b>Auto-dispatch</b>: Multiple {@link Entrypoint} methods with explicit
 *       {@link Purpose} values. The compiler generates a ScriptInfo tag dispatch.</li>
 *   <li><b>Manual dispatch</b>: A single {@link Entrypoint} method with
 *       {@code purpose = Purpose.DEFAULT}. The user switches on ScriptInfo manually.</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MultiValidator {
}
