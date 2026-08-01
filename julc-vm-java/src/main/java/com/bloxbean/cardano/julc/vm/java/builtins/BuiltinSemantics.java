package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.BuiltinSemanticsVariant;
import com.bloxbean.cardano.julc.vm.ProtocolFeatureProfile;
import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.asByteString;
import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.asInteger;
import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.mkByteString;

/** Shared protocol-sensitive builtin runtime boundary for every JuLC backend. */
public final class BuiltinSemantics {

    private static final BigInteger CARDANO_INTEGER_LIMIT = BigInteger.ONE.shiftLeft(262143);
    private static final BigInteger CARDANO_INTEGER_MIN = CARDANO_INTEGER_LIMIT.negate();
    private static final BigInteger CARDANO_INTEGER_MAX = CARDANO_INTEGER_LIMIT.subtract(BigInteger.ONE);
    private static final int CARDANO_BYTESTRING_MAX = 65536;
    private static final BigInteger INT64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger WORD64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger BLS_SCALAR_LIMIT = BigInteger.ONE.shiftLeft(4095);
    private static final BigInteger BLS_SCALAR_MIN = BLS_SCALAR_LIMIT.negate();
    private static final BigInteger BLS_SCALAR_MAX = BLS_SCALAR_LIMIT.subtract(BigInteger.ONE);

    private BuiltinSemantics() {
    }

    /** Validate/unlift with the profile and execute the selected denotation. */
    public static CekValue execute(ProtocolFeatureProfile profile, DefaultFun fun,
                                   BuiltinRuntime runtime, List<CekValue> args) {
        var variant = profile.semanticsVariant();
        if (variant.usesCardanoBounds()) {
            validateCardanoInputs(fun, args);
        }

        if (fun == DefaultFun.ConsByteString && usesModuloCons(variant)) {
            return consByteStringModulo(args);
        }
        return runtime.execute(args);
    }

    private static void validateCardanoInputs(DefaultFun fun, List<CekValue> args) {
        switch (fun) {
            case AddInteger, SubtractInteger, MultiplyInteger,
                    DivideInteger, QuotientInteger, RemainderInteger, ModInteger,
                    LessThanInteger, LessThanEqualsInteger -> {
                requireCardanoInteger(fun, args, 0);
                requireCardanoInteger(fun, args, 1);
            }
            case AppendByteString, EqualsByteString,
                    LessThanByteString, LessThanEqualsByteString -> {
                requireCardanoByteString(fun, args, 0);
                requireCardanoByteString(fun, args, 1);
            }
            case ConsByteString -> {
                requireCardanoInteger(fun, args, 0);
                requireCardanoByteString(fun, args, 1);
            }
            case SliceByteString -> requireCardanoByteString(fun, args, 2);
            case IndexByteString, Sha2_256, Sha3_256, Blake2b_256, DecodeUtf8,
                    Keccak_256, Blake2b_224, ComplementByteString, ReadBit,
                    CountSetBits, FindFirstSetBit, Ripemd_160 ->
                    requireCardanoByteString(fun, args, 0);
            case VerifyEd25519Signature, VerifySchnorrSecp256k1Signature ->
                    requireCardanoByteString(fun, args, 1);
            case Bls12_381_G1_hashToGroup, Bls12_381_G2_hashToGroup ->
                    requireCardanoByteString(fun, args, 0);
            case ByteStringToInteger -> requireCardanoByteString(fun, args, 1);
            case AndByteString, OrByteString, XorByteString -> {
                requireCardanoByteString(fun, args, 1);
                requireCardanoByteString(fun, args, 2);
            }
            case ConstrData -> requireWord64(fun, args, 0);
            case WriteBits -> {
                byte[] bytes = asByteString(args.get(0), fun.name());
                if (bytes.length > 4096) {
                    throw new BuiltinException(fun + ": input exceeds 4096-byte bound");
                }
            }
            case ShiftByteString, RotateByteString -> requireInt64(fun, args, 1);
            case Bls12_381_G1_scalarMul, Bls12_381_G2_scalarMul ->
                    requireBlsScalar(fun, args, 0);
            default -> { }
        }
    }

    private static boolean usesModuloCons(BuiltinSemanticsVariant variant) {
        return variant == BuiltinSemanticsVariant.A
                || variant == BuiltinSemanticsVariant.B
                || variant == BuiltinSemanticsVariant.D;
    }

    private static CekValue consByteStringModulo(List<CekValue> args) {
        BigInteger integer = asInteger(args.get(0), DefaultFun.ConsByteString.name());
        byte[] bytes = asByteString(args.get(1), DefaultFun.ConsByteString.name());
        byte[] result = new byte[bytes.length + 1];
        result[0] = integer.byteValue();
        System.arraycopy(bytes, 0, result, 1, bytes.length);
        return mkByteString(result);
    }

    private static void requireCardanoInteger(
            DefaultFun fun, List<CekValue> args, int index) {
        BigInteger integer = asInteger(args.get(index), fun.name());
        if (integer.compareTo(CARDANO_INTEGER_MIN) < 0
                || integer.compareTo(CARDANO_INTEGER_MAX) > 0) {
            throw new BuiltinException(fun + ": Integer out of bounds");
        }
    }

    private static void requireCardanoByteString(
            DefaultFun fun, List<CekValue> args, int index) {
        byte[] bytes = asByteString(args.get(index), fun.name());
        if (bytes.length > CARDANO_BYTESTRING_MAX) {
            throw new BuiltinException(fun + ": ByteString overflow");
        }
    }

    private static void requireInt64(DefaultFun fun, List<CekValue> args, int index) {
        BigInteger integer = asInteger(args.get(index), fun.name());
        if (integer.compareTo(INT64_MIN) < 0 || integer.compareTo(INT64_MAX) > 0) {
            throw new BuiltinException(fun + ": integer does not fit in signed 64-bit Int");
        }
    }

    private static void requireWord64(DefaultFun fun, List<CekValue> args, int index) {
        BigInteger integer = asInteger(args.get(index), fun.name());
        if (integer.signum() < 0 || integer.compareTo(WORD64_MAX) > 0) {
            throw new BuiltinException(fun + ": integer does not fit in Word64");
        }
    }

    private static void requireBlsScalar(DefaultFun fun, List<CekValue> args, int index) {
        BigInteger scalar = asInteger(args.get(index), fun.name());
        if (scalar.compareTo(BLS_SCALAR_MIN) < 0 || scalar.compareTo(BLS_SCALAR_MAX) > 0) {
            throw new BuiltinException(fun + ": scalar exceeds 512-byte bound");
        }
    }
}
