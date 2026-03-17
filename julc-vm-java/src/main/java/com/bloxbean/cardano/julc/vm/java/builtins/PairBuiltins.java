package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * Pair operation builtins.
 */
public final class PairBuiltins {

    private PairBuiltins() {}

    /**
     * FstPair: force=2, arity=1 → (pair)
     * Returns the first element of a pair.
     */
    public static CekValue fstPair(List<CekValue> args) {
        var pair = asPair(args.get(0), "FstPair");
        return new CekValue.VCon(pair.first());
    }

    /**
     * SndPair: force=2, arity=1 → (pair)
     * Returns the second element of a pair.
     */
    public static CekValue sndPair(List<CekValue> args) {
        var pair = asPair(args.get(0), "SndPair");
        return new CekValue.VCon(pair.second());
    }
}
