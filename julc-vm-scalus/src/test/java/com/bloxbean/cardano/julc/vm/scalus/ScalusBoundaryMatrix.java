package com.bloxbean.cardano.julc.vm.scalus;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.stream.Stream;

/** Test-only matrix derived from Plutus Builtins.hs at f92b7d7d8. */
final class ScalusBoundaryMatrix {

    static final BigInteger CARDANO_INTEGER_LIMIT = BigInteger.ONE.shiftLeft(262_143);
    static final int CARDANO_BYTESTRING_LIMIT = 65_536;

    private static final Constant ZERO = Constant.integer(0);
    private static final Constant ONE = Constant.integer(1);
    private static final Constant EMPTY_BYTES = Constant.byteString(new byte[0]);
    private static final Constant ED25519_PUBLIC_KEY = bytes(
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025");
    private static final Constant ED25519_SIGNATURE = bytes(
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac"
                    + "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a");
    private static final Constant SCHNORR_PUBLIC_KEY = bytes(
            "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9");
    private static final Constant SCHNORR_SIGNATURE = bytes(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821"
                    + "525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0");

    private ScalusBoundaryMatrix() {}

    record IntegerCase(
            DefaultFun builtin, int position, int plutusLine,
            Function<BigInteger, Program> program) {
        @Override public String toString() {
            return builtin + " arg" + position + " CInteger Builtins.hs:" + plutusLine;
        }
    }

    record ByteStringCase(
            DefaultFun builtin, int position, int plutusLine,
            Function<byte[], Program> program) {
        @Override public String toString() {
            return builtin + " arg" + position + " CByteString Builtins.hs:" + plutusLine;
        }
    }

    static Stream<IntegerCase> integerCases() {
        return Stream.of(
                integerBinary(DefaultFun.AddInteger, 1, 1091, ZERO),
                integerBinary(DefaultFun.AddInteger, 2, 1091, ZERO),
                integerBinary(DefaultFun.SubtractInteger, 1, 1106, ZERO),
                integerBinary(DefaultFun.SubtractInteger, 2, 1106, ZERO),
                integerBinary(DefaultFun.MultiplyInteger, 1, 1121, ONE),
                integerBinary(DefaultFun.MultiplyInteger, 2, 1121, ONE),
                integerBinary(DefaultFun.DivideInteger, 1, 1136, ONE),
                integerBinary(DefaultFun.DivideInteger, 2, 1136, ONE),
                integerBinary(DefaultFun.QuotientInteger, 1, 1151, ONE),
                integerBinary(DefaultFun.QuotientInteger, 2, 1151, ONE),
                integerBinary(DefaultFun.RemainderInteger, 1, 1166, ONE),
                integerBinary(DefaultFun.RemainderInteger, 2, 1166, ONE),
                integerBinary(DefaultFun.ModInteger, 1, 1181, ONE),
                integerBinary(DefaultFun.ModInteger, 2, 1181, ONE),
                integerBinary(DefaultFun.LessThanInteger, 1, 1203, ZERO),
                integerBinary(DefaultFun.LessThanInteger, 2, 1203, ZERO),
                integerBinary(DefaultFun.LessThanEqualsInteger, 1, 1218, ZERO),
                integerBinary(DefaultFun.LessThanEqualsInteger, 2, 1218, ZERO));
    }

    static Stream<ByteStringCase> byteStringCases() {
        return Stream.of(
                bytesBinary(DefaultFun.AppendByteString, 1, 1234, EMPTY_BYTES),
                bytesBinary(DefaultFun.AppendByteString, 2, 1234, EMPTY_BYTES),
                bytesCase(DefaultFun.ConsByteString, 2, 1281,
                        value -> apply(DefaultFun.ConsByteString, ZERO, bytes(value))),
                bytesCase(DefaultFun.SliceByteString, 3, 1301,
                        value -> apply(DefaultFun.SliceByteString, ZERO, ZERO, bytes(value))),
                bytesCase(DefaultFun.IndexByteString, 1, 1323,
                        value -> apply(DefaultFun.IndexByteString, bytes(value), ZERO)),
                bytesBinary(DefaultFun.EqualsByteString, 1, 1343, EMPTY_BYTES),
                bytesBinary(DefaultFun.EqualsByteString, 2, 1343, EMPTY_BYTES),
                bytesBinary(DefaultFun.LessThanByteString, 1, 1358, EMPTY_BYTES),
                bytesBinary(DefaultFun.LessThanByteString, 2, 1358, EMPTY_BYTES),
                bytesBinary(DefaultFun.LessThanEqualsByteString, 1, 1373, EMPTY_BYTES),
                bytesBinary(DefaultFun.LessThanEqualsByteString, 2, 1373, EMPTY_BYTES),
                bytesUnary(DefaultFun.Sha2_256, 1, 1389),
                bytesUnary(DefaultFun.Sha3_256, 1, 1404),
                bytesUnary(DefaultFun.Blake2b_256, 1, 1419),
                bytesCase(DefaultFun.VerifyEd25519Signature, 2, 1434,
                        value -> apply(DefaultFun.VerifyEd25519Signature,
                                ED25519_PUBLIC_KEY, bytes(value), ED25519_SIGNATURE)),
                bytesCase(DefaultFun.VerifySchnorrSecp256k1Signature, 2, 1479,
                        value -> apply(DefaultFun.VerifySchnorrSecp256k1Signature,
                                SCHNORR_PUBLIC_KEY, bytes(value), SCHNORR_SIGNATURE)),
                bytesUnary(DefaultFun.DecodeUtf8, 1, 1580),
                hashToGroup(DefaultFun.Bls12_381_G1_hashToGroup,
                        DefaultFun.Bls12_381_G1_compress, 1925),
                hashToGroup(DefaultFun.Bls12_381_G2_hashToGroup,
                        DefaultFun.Bls12_381_G2_compress, 1999),
                bytesUnary(DefaultFun.Keccak_256, 1, 2051),
                bytesUnary(DefaultFun.Blake2b_224, 1, 2066),
                bytesCase(DefaultFun.ByteStringToInteger, 2, 2095,
                        value -> apply(DefaultFun.ByteStringToInteger,
                                Constant.bool(true), bytes(value))),
                logicalBytes(DefaultFun.AndByteString, 2, 2111),
                logicalBytes(DefaultFun.AndByteString, 3, 2111),
                logicalBytes(DefaultFun.OrByteString, 2, 2127),
                logicalBytes(DefaultFun.OrByteString, 3, 2127),
                logicalBytes(DefaultFun.XorByteString, 2, 2143),
                logicalBytes(DefaultFun.XorByteString, 3, 2143),
                bytesUnary(DefaultFun.ComplementByteString, 1, 2159),
                bytesCase(DefaultFun.ReadBit, 1, 2176,
                        value -> apply(DefaultFun.ReadBit, bytes(value), ZERO)),
                bytesUnary(DefaultFun.CountSetBits, 1, 2259),
                bytesUnary(DefaultFun.FindFirstSetBit, 1, 2274),
                bytesUnary(DefaultFun.Ripemd_160, 1, 2289));
    }

    private static IntegerCase integerBinary(
            DefaultFun builtin, int position, int line, Constant other) {
        return new IntegerCase(builtin, position, line, value -> position == 1
                ? apply(builtin, Constant.integer(value), other)
                : apply(builtin, other, Constant.integer(value)));
    }

    private static ByteStringCase bytesUnary(DefaultFun builtin, int position, int line) {
        return bytesCase(builtin, position, line,
                value -> apply(builtin, bytes(value)));
    }

    private static ByteStringCase bytesBinary(
            DefaultFun builtin, int position, int line, Constant other) {
        return bytesCase(builtin, position, line, value -> position == 1
                ? apply(builtin, bytes(value), other)
                : apply(builtin, other, bytes(value)));
    }

    private static ByteStringCase logicalBytes(DefaultFun builtin, int position, int line) {
        return bytesCase(builtin, position, line, value -> position == 2
                ? apply(builtin, Constant.bool(false), bytes(value), EMPTY_BYTES)
                : apply(builtin, Constant.bool(false), EMPTY_BYTES, bytes(value)));
    }

    private static ByteStringCase hashToGroup(
            DefaultFun hash, DefaultFun compress, int line) {
        return bytesCase(hash, 1, line, value -> {
            var hashed = applyTerm(hash, bytes(value), EMPTY_BYTES);
            return Program.plutusV3(new Term.Apply(Term.builtin(compress), hashed));
        });
    }

    private static ByteStringCase bytesCase(
            DefaultFun builtin, int position, int line,
            Function<byte[], Program> program) {
        return new ByteStringCase(builtin, position, line, program);
    }

    static Program apply(DefaultFun builtin, Constant... arguments) {
        return Program.plutusV3(applyTerm(builtin, arguments));
    }

    private static Term applyTerm(DefaultFun builtin, Constant... arguments) {
        Term term = Term.builtin(builtin);
        for (var argument : arguments) {
            term = new Term.Apply(term, Term.const_(argument));
        }
        return term;
    }

    private static Constant bytes(byte[] value) {
        return Constant.byteString(value);
    }

    private static Constant bytes(String hex) {
        return bytes(HexFormat.of().parseHex(hex));
    }
}
