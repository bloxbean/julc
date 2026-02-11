package com.bloxbean.cardano.julc.vm;

/**
 * The Plutus language version used for script evaluation.
 * <p>
 * Different language versions affect:
 * <ul>
 *   <li>Available built-in functions</li>
 *   <li>Cost model parameters</li>
 *   <li>Result validation (V3+ requires Unit result)</li>
 *   <li>Script argument application order</li>
 * </ul>
 */
public enum PlutusLanguage {
    /** Plutus V1 — Alonzo era. UPLC version 1.0.0. */
    PLUTUS_V1(1, 0, 0),

    /** Plutus V2 — Babbage era. UPLC version 1.0.0. */
    PLUTUS_V2(1, 0, 0),

    /** Plutus V3 — Conway era. UPLC version 1.1.0 (adds Constr/Case). */
    PLUTUS_V3(1, 1, 0);

    private final int uplcMajor;
    private final int uplcMinor;
    private final int uplcPatch;

    PlutusLanguage(int uplcMajor, int uplcMinor, int uplcPatch) {
        this.uplcMajor = uplcMajor;
        this.uplcMinor = uplcMinor;
        this.uplcPatch = uplcPatch;
    }

    /** The UPLC major version for this language. */
    public int uplcMajor() { return uplcMajor; }

    /** The UPLC minor version for this language. */
    public int uplcMinor() { return uplcMinor; }

    /** The UPLC patch version for this language. */
    public int uplcPatch() { return uplcPatch; }
}
