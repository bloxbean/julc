/**
 * Stable, closed Java construction API for JuLC verification properties.
 *
 * <p>The expression wrappers, {@code VerificationDsl},
 * {@code VerificationSpecification}, and compiler-generated metamodels are the
 * supported construction surface. Concrete classes in the {@code dsl.ir}
 * subpackage are serialized implementation infrastructure unless explicitly
 * named by the public guide.</p>
 *
 * <p>Property builders execute trusted project Java in a bounded worker. API
 * stability does not promise solver termination or general contract safety.</p>
 */
package com.bloxbean.cardano.julc.verification.dsl;
