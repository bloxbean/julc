package com.bloxbean.cardano.julc.core.types;

import com.bloxbean.cardano.julc.core.PlutusData;

/**
 * A 2-element tuple.
 * <p>
 * On-chain: compiled to {@code ConstrData(0, [first, second])}.
 * Pattern matching via {@code switch (result) { case Tuple2(var a, var b) -> ... }} works.
 */
public record Tuple2(PlutusData first, PlutusData second) {}
