package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

/** Version markers for the supported typed verification DSL construction API. */
public final class VerificationDslApi {
    /** Stable Java construction API introduced by ADR-029. */
    public static final int API_VERSION = 1;

    /** Default and fully reviewed property IR emitted for new specifications. */
    public static final int STABLE_PROPERTY_SCHEMA_VERSION =
            DslPropertySet.REVIEWED_DATA_ADAPTER_SCHEMA_VERSION;

    /** Oldest property schema retained by the canonical reader. */
    public static final int MIN_READABLE_PROPERTY_SCHEMA_VERSION =
            DslPropertySet.SCHEMA_VERSION;

    private VerificationDslApi() { }
}
