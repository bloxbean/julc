package com.bloxbean.cardano.julc.core.types;

import com.bloxbean.cardano.julc.core.PlutusData;

/**
 * A 3-element tuple.
 * <p>
 * On-chain: compiled to {@code ConstrData(0, [first, second, third])}.
 * Pattern matching via {@code switch (result) { case Tuple3(var a, var b, var c) -> ... }} works.
 */
public record Tuple3(PlutusData first, PlutusData second, PlutusData third) {}
