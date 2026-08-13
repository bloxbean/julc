package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

/** Explicitly executed property builder entry point for the bounded worker. */
public interface VerificationSpecification {
    DslPropertySet properties();
}
