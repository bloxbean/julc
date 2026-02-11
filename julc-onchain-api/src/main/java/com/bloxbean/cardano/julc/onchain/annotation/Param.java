package com.bloxbean.cardano.julc.onchain.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as a contract parameter that gets "baked in" at deploy time
 * via UPLC partial application.
 * <p>
 * Each unique set of parameter values produces a different script hash/address.
 * Parameters are applied in declaration order.
 * <p>
 * Example:
 * <pre>{@code
 * @Validator
 * class TokenValidator {
 *     @Param byte[] tokenPolicyId;
 *     @Param BigInteger minAmount;
 *
 *     @Entrypoint
 *     static boolean validate(PlutusData redeemer, ScriptContext ctx) {
 *         return minAmount > 0;
 *     }
 * }
 * }</pre>
 *
 * @see Validator
 * @see MintingPolicy
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Param {
}
