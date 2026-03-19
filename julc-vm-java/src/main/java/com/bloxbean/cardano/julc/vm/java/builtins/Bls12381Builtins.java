package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.bls.BlsException;
import com.bloxbean.cardano.julc.bls.BlsOperations;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * BLS12-381 builtin implementations using the blst native library.
 * <p>
 * Each method extracts typed values from CekValue arguments, delegates to
 * {@link BlsOperations} for the actual cryptographic operation, and wraps
 * the result back in a CekValue.
 */
public final class Bls12381Builtins {

    private Bls12381Builtins() {}

    // ====== G1 Operations ======

    public static CekValue g1Add(List<CekValue> args) {
        byte[] a = asG1Element(args.get(0), "Bls12_381_G1_add");
        byte[] b = asG1Element(args.get(1), "Bls12_381_G1_add");
        return bls("Bls12_381_G1_add", () -> mkG1Element(BlsOperations.g1Add(a, b)));
    }

    public static CekValue g1Neg(List<CekValue> args) {
        byte[] a = asG1Element(args.get(0), "Bls12_381_G1_neg");
        return bls("Bls12_381_G1_neg", () -> mkG1Element(BlsOperations.g1Neg(a)));
    }

    public static CekValue g1ScalarMul(List<CekValue> args) {
        BigInteger scalar = asInteger(args.get(0), "Bls12_381_G1_scalarMul");
        byte[] point = asG1Element(args.get(1), "Bls12_381_G1_scalarMul");
        return bls("Bls12_381_G1_scalarMul", () -> mkG1Element(BlsOperations.g1ScalarMul(scalar, point)));
    }

    public static CekValue g1Equal(List<CekValue> args) {
        byte[] a = asG1Element(args.get(0), "Bls12_381_G1_equal");
        byte[] b = asG1Element(args.get(1), "Bls12_381_G1_equal");
        return bls("Bls12_381_G1_equal", () -> mkBool(BlsOperations.g1Equal(a, b)));
    }

    public static CekValue g1Compress(List<CekValue> args) {
        byte[] element = asG1Element(args.get(0), "Bls12_381_G1_compress");
        return mkByteString(BlsOperations.g1Compress(element));
    }

    public static CekValue g1Uncompress(List<CekValue> args) {
        byte[] compressed = asByteString(args.get(0), "Bls12_381_G1_uncompress");
        return bls("Bls12_381_G1_uncompress", () -> mkG1Element(BlsOperations.g1Uncompress(compressed)));
    }

    public static CekValue g1HashToGroup(List<CekValue> args) {
        byte[] msg = asByteString(args.get(0), "Bls12_381_G1_hashToGroup");
        byte[] dst = asByteString(args.get(1), "Bls12_381_G1_hashToGroup");
        return bls("Bls12_381_G1_hashToGroup", () -> mkG1Element(BlsOperations.g1HashToGroup(msg, dst)));
    }

    // ====== G2 Operations ======

    public static CekValue g2Add(List<CekValue> args) {
        byte[] a = asG2Element(args.get(0), "Bls12_381_G2_add");
        byte[] b = asG2Element(args.get(1), "Bls12_381_G2_add");
        return bls("Bls12_381_G2_add", () -> mkG2Element(BlsOperations.g2Add(a, b)));
    }

    public static CekValue g2Neg(List<CekValue> args) {
        byte[] a = asG2Element(args.get(0), "Bls12_381_G2_neg");
        return bls("Bls12_381_G2_neg", () -> mkG2Element(BlsOperations.g2Neg(a)));
    }

    public static CekValue g2ScalarMul(List<CekValue> args) {
        BigInteger scalar = asInteger(args.get(0), "Bls12_381_G2_scalarMul");
        byte[] point = asG2Element(args.get(1), "Bls12_381_G2_scalarMul");
        return bls("Bls12_381_G2_scalarMul", () -> mkG2Element(BlsOperations.g2ScalarMul(scalar, point)));
    }

    public static CekValue g2Equal(List<CekValue> args) {
        byte[] a = asG2Element(args.get(0), "Bls12_381_G2_equal");
        byte[] b = asG2Element(args.get(1), "Bls12_381_G2_equal");
        return bls("Bls12_381_G2_equal", () -> mkBool(BlsOperations.g2Equal(a, b)));
    }

    public static CekValue g2Compress(List<CekValue> args) {
        byte[] element = asG2Element(args.get(0), "Bls12_381_G2_compress");
        return mkByteString(BlsOperations.g2Compress(element));
    }

    public static CekValue g2Uncompress(List<CekValue> args) {
        byte[] compressed = asByteString(args.get(0), "Bls12_381_G2_uncompress");
        return bls("Bls12_381_G2_uncompress", () -> mkG2Element(BlsOperations.g2Uncompress(compressed)));
    }

    public static CekValue g2HashToGroup(List<CekValue> args) {
        byte[] msg = asByteString(args.get(0), "Bls12_381_G2_hashToGroup");
        byte[] dst = asByteString(args.get(1), "Bls12_381_G2_hashToGroup");
        return bls("Bls12_381_G2_hashToGroup", () -> mkG2Element(BlsOperations.g2HashToGroup(msg, dst)));
    }

    // ====== Pairing Operations ======

    public static CekValue millerLoop(List<CekValue> args) {
        byte[] g1 = asG1Element(args.get(0), "Bls12_381_millerLoop");
        byte[] g2 = asG2Element(args.get(1), "Bls12_381_millerLoop");
        return bls("Bls12_381_millerLoop", () -> mkMlResult(BlsOperations.millerLoop(g1, g2)));
    }

    public static CekValue mulMlResult(List<CekValue> args) {
        byte[] a = asMlResult(args.get(0), "Bls12_381_mulMlResult");
        byte[] b = asMlResult(args.get(1), "Bls12_381_mulMlResult");
        return bls("Bls12_381_mulMlResult", () -> mkMlResult(BlsOperations.mulMlResult(a, b)));
    }

    public static CekValue finalVerify(List<CekValue> args) {
        byte[] a = asMlResult(args.get(0), "Bls12_381_finalVerify");
        byte[] b = asMlResult(args.get(1), "Bls12_381_finalVerify");
        return bls("Bls12_381_finalVerify", () -> mkBool(BlsOperations.finalVerify(a, b)));
    }

    // ====== Multi-Scalar Multiplication (CIP-133) ======

    public static CekValue g1MultiScalarMul(List<CekValue> args) {
        var scalarsList = asListConst(args.get(0), "Bls12_381_G1_multiScalarMul");
        var pointsList = asListConst(args.get(1), "Bls12_381_G1_multiScalarMul");

        BigInteger[] scalars = new BigInteger[scalarsList.values().size()];
        for (int i = 0; i < scalars.length; i++) {
            if (!(scalarsList.values().get(i) instanceof Constant.IntegerConst ic)) {
                throw new BuiltinException("Bls12_381_G1_multiScalarMul: scalar must be integer");
            }
            scalars[i] = ic.value();
        }

        byte[][] points = new byte[pointsList.values().size()][];
        for (int i = 0; i < points.length; i++) {
            if (!(pointsList.values().get(i) instanceof Constant.Bls12_381_G1Element g1)) {
                throw new BuiltinException("Bls12_381_G1_multiScalarMul: point must be G1 element");
            }
            points[i] = g1.value();
        }

        return bls("Bls12_381_G1_multiScalarMul", () -> mkG1Element(BlsOperations.g1MultiScalarMul(scalars, points)));
    }

    public static CekValue g2MultiScalarMul(List<CekValue> args) {
        var scalarsList = asListConst(args.get(0), "Bls12_381_G2_multiScalarMul");
        var pointsList = asListConst(args.get(1), "Bls12_381_G2_multiScalarMul");

        BigInteger[] scalars = new BigInteger[scalarsList.values().size()];
        for (int i = 0; i < scalars.length; i++) {
            if (!(scalarsList.values().get(i) instanceof Constant.IntegerConst ic)) {
                throw new BuiltinException("Bls12_381_G2_multiScalarMul: scalar must be integer");
            }
            scalars[i] = ic.value();
        }

        byte[][] points = new byte[pointsList.values().size()][];
        for (int i = 0; i < points.length; i++) {
            if (!(pointsList.values().get(i) instanceof Constant.Bls12_381_G2Element g2)) {
                throw new BuiltinException("Bls12_381_G2_multiScalarMul: point must be G2 element");
            }
            points[i] = g2.value();
        }

        return bls("Bls12_381_G2_multiScalarMul", () -> mkG2Element(BlsOperations.g2MultiScalarMul(scalars, points)));
    }

    // ====== Internal ======

    @FunctionalInterface
    private interface BlsCall {
        CekValue execute();
    }

    private static CekValue bls(String name, BlsCall call) {
        try {
            return call.execute();
        } catch (BlsException e) {
            throw new BuiltinException(name + ": " + e.getMessage(), e);
        }
    }
}
