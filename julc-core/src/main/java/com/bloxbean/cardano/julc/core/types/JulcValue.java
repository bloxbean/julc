package com.bloxbean.cardano.julc.core.types;

/**
 * Opaque native UPLC Value produced by the PV11 MaryEraValue builtins.
 *
 * <p>This is deliberately not {@code PlutusData} and not the ledger API's
 * Data-encoded {@code Value}. Native values must cross a Data boundary through
 * explicit {@code Builtins.unValueData}/{@code Builtins.valueData} calls.
 * There is no JVM constructor because native Value semantics belong to the
 * ledger evaluator.
 */
public final class JulcValue {

    private JulcValue() {
    }
}
