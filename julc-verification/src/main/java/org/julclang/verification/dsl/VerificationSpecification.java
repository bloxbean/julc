package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.DslPropertySet;

/** Explicitly executed property builder entry point for the bounded worker. */
public interface VerificationSpecification {
    DslPropertySet properties();
}
