package com.bloxbean.cardano.julc.onchain.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Plutus withdraw (rewarding) validator.
 * <p>
 * The class must contain exactly one {@link Entrypoint} method.
 * Withdraw validators receive {@code (redeemer, scriptContext)} parameters
 * and validate staking reward withdrawals.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WithdrawValidator {
}
