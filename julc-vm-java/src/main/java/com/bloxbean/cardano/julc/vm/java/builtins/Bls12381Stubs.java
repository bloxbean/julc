package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.util.List;

/**
 * BLS12-381 builtin stubs — all throw UnsupportedBuiltinException.
 * <p>
 * These will be implemented in a future phase. The stubs allow the
 * builtin table to be complete while properly reporting unsupported operations.
 */
public final class Bls12381Stubs {

    private Bls12381Stubs() {}

    private static CekValue unsupported(String name) {
        throw new UnsupportedBuiltinException("BLS12-381 not yet implemented: " + name);
    }

    public static CekValue g1Add(List<CekValue> args) { return unsupported("Bls12_381_G1_add"); }
    public static CekValue g1Neg(List<CekValue> args) { return unsupported("Bls12_381_G1_neg"); }
    public static CekValue g1ScalarMul(List<CekValue> args) { return unsupported("Bls12_381_G1_scalarMul"); }
    public static CekValue g1Equal(List<CekValue> args) { return unsupported("Bls12_381_G1_equal"); }
    public static CekValue g1Compress(List<CekValue> args) { return unsupported("Bls12_381_G1_compress"); }
    public static CekValue g1Uncompress(List<CekValue> args) { return unsupported("Bls12_381_G1_uncompress"); }
    public static CekValue g1HashToGroup(List<CekValue> args) { return unsupported("Bls12_381_G1_hashToGroup"); }
    public static CekValue g2Add(List<CekValue> args) { return unsupported("Bls12_381_G2_add"); }
    public static CekValue g2Neg(List<CekValue> args) { return unsupported("Bls12_381_G2_neg"); }
    public static CekValue g2ScalarMul(List<CekValue> args) { return unsupported("Bls12_381_G2_scalarMul"); }
    public static CekValue g2Equal(List<CekValue> args) { return unsupported("Bls12_381_G2_equal"); }
    public static CekValue g2Compress(List<CekValue> args) { return unsupported("Bls12_381_G2_compress"); }
    public static CekValue g2Uncompress(List<CekValue> args) { return unsupported("Bls12_381_G2_uncompress"); }
    public static CekValue g2HashToGroup(List<CekValue> args) { return unsupported("Bls12_381_G2_hashToGroup"); }
    public static CekValue millerLoop(List<CekValue> args) { return unsupported("Bls12_381_millerLoop"); }
    public static CekValue mulMlResult(List<CekValue> args) { return unsupported("Bls12_381_mulMlResult"); }
    public static CekValue finalVerify(List<CekValue> args) { return unsupported("Bls12_381_finalVerify"); }
}
