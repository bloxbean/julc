package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;

import java.util.EnumMap;
import java.util.Map;

import static com.bloxbean.cardano.julc.vm.java.cost.CostFunction.*;

/**
 * Parses a flat cost model parameter array (as stored on-chain in Cardano protocol parameters)
 * into {@link MachineCosts} and {@link BuiltinCostModel}.
 * <p>
 * The flat array follows the canonical ordering defined by the Plutus specification
 * ({@code PlutusLedgerApi.V3.ParamName} in the Haskell source). This is NOT purely
 * alphabetical — V3 and Plomin additions are appended at the end for backward compatibility.
 * <p>
 * Machine costs are split: 8 original CEK step types at indices 17–32, and
 * CONSTR/CASE at indices 193–196.
 */
public final class CostModelParser {

    /** Result of parsing a flat cost model array. */
    public record ParsedCostModel(MachineCosts machineCosts, BuiltinCostModel builtinCostModel) {}

    /** Expected parameter count for PlutusV1 (Alonzo era). */
    public static final int V1_PARAM_COUNT = 166;

    /** Expected parameter count for PlutusV2 (Babbage era). */
    public static final int V2_PARAM_COUNT = 175;

    /** Expected parameter count for PlutusV3 PV10 (post-Plomin, current mainnet). */
    public static final int PV10_PARAM_COUNT = 297;

    /**
     * Expected parameter count for PlutusV3 PV11 (post-Chang+2).
     * <p>
     * Adds 53 params: ExpModInteger (5, moved from defaults-only to array) + 13 PV11 builtins (48).
     * Canonical ordering from Haskell ParamName.hs.
     */
    public static final int PV11_PARAM_COUNT = 350;

    private CostModelParser() {}

    /**
     * Parse a flat cost model parameter array for the specified Plutus language version.
     * Uses default protocol version 10.0.
     *
     * @param values   the flat cost model array from protocol parameters
     * @param language the Plutus language version
     * @return parsed machine costs and builtin cost model
     * @throws IllegalArgumentException if the array is too short
     */
    public static ParsedCostModel parse(long[] values, PlutusLanguage language) {
        return parse(values, language, 10, 0);
    }

    /**
     * Parse a flat cost model parameter array for the specified Plutus language version
     * and protocol version.
     * <p>
     * The protocol version determines the expected parameter count for ALL language
     * versions (V1/V2/V3), since new builtins are added to all versions in each
     * protocol version. For example, PV10 (Plomin) added bitwise builtins to V1/V2/V3,
     * increasing the parameter count for all three.
     * <p>
     * Currently supported:
     * <ul>
     *   <li>V1 PV9: 166 params, PV10: 166+ (extra params accepted but unused)</li>
     *   <li>V2 PV9: 175 params, PV10: 175+ (extra params accepted but unused)</li>
     *   <li>V3 PV10: 297 params, PV11: 350 params</li>
     * </ul>
     *
     * @param values               the flat cost model array from protocol parameters
     * @param language             the Plutus language version
     * @param protocolMajorVersion the protocol major version (e.g. 9 for Chang, 10 for Plomin)
     * @param protocolMinorVersion the protocol minor version
     * @return parsed machine costs and builtin cost model
     * @throws IllegalArgumentException if the array is too short
     */
    public static ParsedCostModel parse(long[] values, PlutusLanguage language,
                                         int protocolMajorVersion, int protocolMinorVersion) {
        return switch (language) {
            case PLUTUS_V1 -> parseV1(values, protocolMajorVersion, protocolMinorVersion);
            case PLUTUS_V2 -> parseV2(values, protocolMajorVersion, protocolMinorVersion);
            case PLUTUS_V3 -> parse(values, protocolMajorVersion);
        };
    }

    /**
     * Parse a PlutusV1 cost model parameter array.
     * <p>
     * Extracts machine costs from the array. Builtin costs use V1 defaults
     * since the V1 cost function shapes may differ from V3.
     * <p>
     * The protocol version is reserved for future use — when new builtins are
     * added to V1 in a future protocol version, this method will branch on the
     * version to parse the additional cost parameters.
     *
     * @param protocolMajorVersion protocol major version
     * @param protocolMinorVersion protocol minor version
     */
    private static ParsedCostModel parseV1(long[] values, int protocolMajorVersion,
                                            int protocolMinorVersion) {
        // At PV11, V1/V2 arrays grow too — accept longer arrays but use defaults for new builtins.
        // TODO: Parse additional V1 PV11 params when exact V1 PV11 param counts are verified.
        if (values.length < V1_PARAM_COUNT) {
            throw new IllegalArgumentException(
                    "PlutusV1 cost model requires at least " + V1_PARAM_COUNT +
                    " parameters, got " + values.length);
        }
        // V1 machine costs are at fixed positions within the 166-param array.
        // The alphabetical ordering places them at the same relative positions
        // as V3 (after the first 5 builtins), but offset differs from V3 due to
        // different cost function shapes for V1 builtins.
        // For correctness, use V1 defaults for builtin costs and extract machine
        // costs from the known position.
        // V1 machine costs are at indices 17-32 in the V1 flat array
        // (same position as V3 — after AddInteger, AppendByteString, AppendString, BData, Blake2b_256)
        // These 5 builtins consume 17 params in both V1 and V3 (same cost shapes for these).
        long applyCpu = values[17];   long applyMem = values[18];
        long builtinCpu = values[19]; long builtinMem = values[20];
        long constCpu = values[21];   long constMem = values[22];
        long delayCpu = values[23];   long delayMem = values[24];
        long forceCpu = values[25];   long forceMem = values[26];
        long lamCpu = values[27];     long lamMem = values[28];
        long startupCpu = values[29]; long startupMem = values[30];
        long varCpu = values[31];     long varMem = values[32];

        MachineCosts mc = new MachineCosts(
                startupCpu, startupMem,
                varCpu, varMem,
                lamCpu, lamMem,
                applyCpu, applyMem,
                forceCpu, forceMem,
                delayCpu, delayMem,
                constCpu, constMem,
                builtinCpu, builtinMem,
                0, 0,  // constrCpu/Mem — not available in V1
                0, 0   // caseCpu/Mem — not available in V1
        );

        return new ParsedCostModel(mc, DefaultCostModel.defaultBuiltinCostModel(PlutusLanguage.PLUTUS_V1));
    }

    /**
     * Parse a PlutusV2 cost model parameter array.
     * <p>
     * Extracts machine costs from the array. Builtin costs use V2 defaults
     * since the V2 cost function shapes may differ from V3.
     * <p>
     * The protocol version is reserved for future use — when new builtins are
     * added to V2 in a future protocol version, this method will branch on the
     * version to parse the additional cost parameters.
     *
     * @param protocolMajorVersion protocol major version
     * @param protocolMinorVersion protocol minor version
     */
    private static ParsedCostModel parseV2(long[] values, int protocolMajorVersion,
                                            int protocolMinorVersion) {
        // At PV11, V1/V2 arrays grow too — accept longer arrays but use defaults for new builtins.
        // TODO: Parse additional V2 PV11 params when exact V2 PV11 param counts are verified.
        if (values.length < V2_PARAM_COUNT) {
            throw new IllegalArgumentException(
                    "PlutusV2 cost model requires at least " + V2_PARAM_COUNT +
                    " parameters, got " + values.length);
        }
        // V2 machine costs are at the same position as V1 (indices 17-32)
        long applyCpu = values[17];   long applyMem = values[18];
        long builtinCpu = values[19]; long builtinMem = values[20];
        long constCpu = values[21];   long constMem = values[22];
        long delayCpu = values[23];   long delayMem = values[24];
        long forceCpu = values[25];   long forceMem = values[26];
        long lamCpu = values[27];     long lamMem = values[28];
        long startupCpu = values[29]; long startupMem = values[30];
        long varCpu = values[31];     long varMem = values[32];

        MachineCosts mc = new MachineCosts(
                startupCpu, startupMem,
                varCpu, varMem,
                lamCpu, lamMem,
                applyCpu, applyMem,
                forceCpu, forceMem,
                delayCpu, delayMem,
                constCpu, constMem,
                builtinCpu, builtinMem,
                0, 0,  // constrCpu/Mem — not available in V2
                0, 0   // caseCpu/Mem — not available in V2
        );

        return new ParsedCostModel(mc, DefaultCostModel.defaultBuiltinCostModel(PlutusLanguage.PLUTUS_V2));
    }

    /**
     * Parse a PlutusV3 flat cost model parameter array into machine costs and builtin cost model.
     * <p>
     * The array must have at least {@link #PV10_PARAM_COUNT} (297) elements.
     * Any builtins not covered by the array (e.g., ExpModInteger in PV10) retain
     * their defaults from {@link DefaultCostModel}.
     *
     * @param values the flat cost model array from protocol parameters
     * @return parsed machine costs and builtin cost model
     * @throws IllegalArgumentException if the array is too short
     */
    public static ParsedCostModel parse(long[] values) {
        return parse(values, 10);
    }

    /**
     * Parse a PlutusV3 flat cost model parameter array with protocol major version.
     * <p>
     * Supports PV10 (297 params) and PV11 (350 params).
     *
     * @param values               the flat cost model array from protocol parameters
     * @param protocolMajorVersion the protocol major version
     * @return parsed machine costs and builtin cost model
     * @throws IllegalArgumentException if the array is too short
     */
    public static ParsedCostModel parse(long[] values, int protocolMajorVersion) {
        if (protocolMajorVersion >= 11) {
            if (values.length < PV11_PARAM_COUNT) {
                throw new IllegalArgumentException(
                        "PlutusV3 PV11 cost model requires at least " + PV11_PARAM_COUNT +
                        " parameters, got " + values.length);
            }
        } else if (values.length < PV10_PARAM_COUNT) {
            throw new IllegalArgumentException(
                    "PlutusV3 cost model requires at least " + PV10_PARAM_COUNT +
                    " parameters, got " + values.length);
        }

        // Start with defaults for builtins not covered by the flat array
        var defaultModel = DefaultCostModel.defaultBuiltinCostModel();
        Map<DefaultFun, BuiltinCostModel.CostPair> costs = new EnumMap<>(DefaultFun.class);
        for (var fun : DefaultFun.values()) {
            var pair = defaultModel.get(fun);
            if (pair != null) {
                costs.put(fun, pair);
            }
        }

        int[] c = {0}; // mutable cursor

        // === V1/V2 builtins (indices 0–16) ===
        // 0-3: AddInteger — MaxSize(cpu) + MaxSize(mem)
        costs.put(DefaultFun.AddInteger, pair(readMaxSize(values, c), readMaxSize(values, c)));
        // 4-7: AppendByteString — AddedSizes(cpu) + AddedSizes(mem)
        costs.put(DefaultFun.AppendByteString, pair(readAddedSizes(values, c), readAddedSizes(values, c)));
        // 8-11: AppendString — AddedSizes(cpu) + AddedSizes(mem)
        costs.put(DefaultFun.AppendString, pair(readAddedSizes(values, c), readAddedSizes(values, c)));
        // 12-13: BData — Const(cpu) + Const(mem)
        costs.put(DefaultFun.BData, pair(readConst(values, c), readConst(values, c)));
        // 14-16: Blake2b_256 — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.Blake2b_256, pair(readLinearInX(values, c), readConst(values, c)));

        // === V1/V2 Machine costs (indices 17–32): 8 step types × 2 ===
        long applyCpu = next(values, c);    long applyMem = next(values, c);
        long builtinCpu = next(values, c);  long builtinMem = next(values, c);
        long constCpu = next(values, c);    long constMem = next(values, c);
        long delayCpu = next(values, c);    long delayMem = next(values, c);
        long forceCpu = next(values, c);    long forceMem = next(values, c);
        long lamCpu = next(values, c);      long lamMem = next(values, c);
        long startupCpu = next(values, c);  long startupMem = next(values, c);
        long varCpu = next(values, c);      long varMem = next(values, c);

        // === V1/V2 builtins continued (indices 33–192) ===
        // 33-34: ChooseData
        costs.put(DefaultFun.ChooseData, pair(readConst(values, c), readConst(values, c)));
        // 35-36: ChooseList
        costs.put(DefaultFun.ChooseList, pair(readConst(values, c), readConst(values, c)));
        // 37-38: ChooseUnit
        costs.put(DefaultFun.ChooseUnit, pair(readConst(values, c), readConst(values, c)));
        // 39-42: ConsByteString — LinearInY(cpu) + AddedSizes(mem)
        costs.put(DefaultFun.ConsByteString, pair(readLinearInY(values, c), readAddedSizes(values, c)));
        // 43-44: ConstrData
        costs.put(DefaultFun.ConstrData, pair(readConst(values, c), readConst(values, c)));
        // 45-48: DecodeUtf8 — LinearInX(cpu) + LinearInX(mem)
        costs.put(DefaultFun.DecodeUtf8, pair(readLinearInX(values, c), readLinearInX(values, c)));
        // 49-59: DivideInteger — ConstAboveDiagonal(8, cpu) + SubtractedSizes(3, mem)
        costs.put(DefaultFun.DivideInteger, pair(readConstAboveDiag(values, c), readSubtractedSizes(values, c)));
        // 60-63: EncodeUtf8 — LinearInX(cpu) + LinearInX(mem)
        costs.put(DefaultFun.EncodeUtf8, pair(readLinearInX(values, c), readLinearInX(values, c)));
        // 64-67: EqualsByteString — LinearOnDiagonal(cpu) + Const(mem)
        costs.put(DefaultFun.EqualsByteString, pair(readLinearOnDiag(values, c), readConst(values, c)));
        // 68-70: EqualsData — MinSize(cpu) + Const(mem)
        costs.put(DefaultFun.EqualsData, pair(readMinSize(values, c), readConst(values, c)));
        // 71-73: EqualsInteger — MinSize(cpu) + Const(mem)
        costs.put(DefaultFun.EqualsInteger, pair(readMinSize(values, c), readConst(values, c)));
        // 74-77: EqualsString — LinearOnDiagonal(cpu) + Const(mem)
        costs.put(DefaultFun.EqualsString, pair(readLinearOnDiag(values, c), readConst(values, c)));
        // 78-79: FstPair
        costs.put(DefaultFun.FstPair, pair(readConst(values, c), readConst(values, c)));
        // 80-81: HeadList
        costs.put(DefaultFun.HeadList, pair(readConst(values, c), readConst(values, c)));
        // 82-83: IData
        costs.put(DefaultFun.IData, pair(readConst(values, c), readConst(values, c)));
        // 84-85: IfThenElse
        costs.put(DefaultFun.IfThenElse, pair(readConst(values, c), readConst(values, c)));
        // 86-87: IndexByteString
        costs.put(DefaultFun.IndexByteString, pair(readConst(values, c), readConst(values, c)));
        // 88-89: LengthOfByteString
        costs.put(DefaultFun.LengthOfByteString, pair(readConst(values, c), readConst(values, c)));
        // 90-92: LessThanByteString — MinSize(cpu) + Const(mem)
        costs.put(DefaultFun.LessThanByteString, pair(readMinSize(values, c), readConst(values, c)));
        // 93-95: LessThanEqualsByteString — MinSize(cpu) + Const(mem)
        costs.put(DefaultFun.LessThanEqualsByteString, pair(readMinSize(values, c), readConst(values, c)));
        // 96-98: LessThanEqualsInteger — MinSize(cpu) + Const(mem)
        costs.put(DefaultFun.LessThanEqualsInteger, pair(readMinSize(values, c), readConst(values, c)));
        // 99-101: LessThanInteger — MinSize(cpu) + Const(mem)
        costs.put(DefaultFun.LessThanInteger, pair(readMinSize(values, c), readConst(values, c)));
        // 102-103: ListData
        costs.put(DefaultFun.ListData, pair(readConst(values, c), readConst(values, c)));
        // 104-105: MapData
        costs.put(DefaultFun.MapData, pair(readConst(values, c), readConst(values, c)));
        // 106-107: MkCons
        costs.put(DefaultFun.MkCons, pair(readConst(values, c), readConst(values, c)));
        // 108-109: MkNilData
        costs.put(DefaultFun.MkNilData, pair(readConst(values, c), readConst(values, c)));
        // 110-111: MkNilPairData
        costs.put(DefaultFun.MkNilPairData, pair(readConst(values, c), readConst(values, c)));
        // 112-113: MkPairData
        costs.put(DefaultFun.MkPairData, pair(readConst(values, c), readConst(values, c)));
        // 114-123: ModInteger — ConstAboveDiagonal(8, cpu) + LinearInY(2, mem)
        costs.put(DefaultFun.ModInteger, pair(readConstAboveDiag(values, c), readLinearInY(values, c)));
        // 124-127: MultiplyInteger — MultipliedSizes(cpu) + AddedSizes(mem)
        costs.put(DefaultFun.MultiplyInteger, pair(readMultipliedSizes(values, c), readAddedSizes(values, c)));
        // 128-129: NullList
        costs.put(DefaultFun.NullList, pair(readConst(values, c), readConst(values, c)));
        // 130-140: QuotientInteger — ConstAboveDiagonal(8, cpu) + SubtractedSizes(3, mem)
        costs.put(DefaultFun.QuotientInteger, pair(readConstAboveDiag(values, c), readSubtractedSizes(values, c)));
        // 141-150: RemainderInteger — ConstAboveDiagonal(8, cpu) + LinearInY(2, mem)
        costs.put(DefaultFun.RemainderInteger, pair(readConstAboveDiag(values, c), readLinearInY(values, c)));
        // 151-154: SerialiseData — LinearInX(cpu) + LinearInX(mem)
        costs.put(DefaultFun.SerialiseData, pair(readLinearInX(values, c), readLinearInX(values, c)));
        // 155-157: Sha2_256 — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.Sha2_256, pair(readLinearInX(values, c), readConst(values, c)));
        // 158-160: Sha3_256 — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.Sha3_256, pair(readLinearInX(values, c), readConst(values, c)));
        // 161-164: SliceByteString — LinearInZ(cpu) + LinearInZ(mem)
        costs.put(DefaultFun.SliceByteString, pair(readLinearInZ(values, c), readLinearInZ(values, c)));
        // 165-166: SndPair
        costs.put(DefaultFun.SndPair, pair(readConst(values, c), readConst(values, c)));
        // 167-170: SubtractInteger — MaxSize(cpu) + MaxSize(mem)
        costs.put(DefaultFun.SubtractInteger, pair(readMaxSize(values, c), readMaxSize(values, c)));
        // 171-172: TailList
        costs.put(DefaultFun.TailList, pair(readConst(values, c), readConst(values, c)));
        // 173-174: Trace
        costs.put(DefaultFun.Trace, pair(readConst(values, c), readConst(values, c)));
        // 175-176: UnBData
        costs.put(DefaultFun.UnBData, pair(readConst(values, c), readConst(values, c)));
        // 177-178: UnConstrData
        costs.put(DefaultFun.UnConstrData, pair(readConst(values, c), readConst(values, c)));
        // 179-180: UnIData
        costs.put(DefaultFun.UnIData, pair(readConst(values, c), readConst(values, c)));
        // 181-182: UnListData
        costs.put(DefaultFun.UnListData, pair(readConst(values, c), readConst(values, c)));
        // 183-184: UnMapData
        costs.put(DefaultFun.UnMapData, pair(readConst(values, c), readConst(values, c)));
        // 185-186: VerifyEcdsaSecp256k1Signature — Const(cpu) + Const(mem)
        costs.put(DefaultFun.VerifyEcdsaSecp256k1Signature, pair(readConst(values, c), readConst(values, c)));
        // 187-189: VerifyEd25519Signature — LinearInY(cpu) + Const(mem)
        costs.put(DefaultFun.VerifyEd25519Signature, pair(readLinearInY(values, c), readConst(values, c)));
        // 190-192: VerifySchnorrSecp256k1Signature — LinearInY(cpu) + Const(mem)
        costs.put(DefaultFun.VerifySchnorrSecp256k1Signature, pair(readLinearInY(values, c), readConst(values, c)));

        // === V3 Machine costs (indices 193–196) ===
        long constrCpu = next(values, c);   long constrMem = next(values, c);
        long caseCpu = next(values, c);     long caseMem = next(values, c);

        // === V3 BLS12-381 + crypto + conversions (indices 197–250) ===
        // 197-198: Bls12_381_G1_add
        costs.put(DefaultFun.Bls12_381_G1_add, pair(readConst(values, c), readConst(values, c)));
        // 199-200: Bls12_381_G1_compress
        costs.put(DefaultFun.Bls12_381_G1_compress, pair(readConst(values, c), readConst(values, c)));
        // 201-202: Bls12_381_G1_equal
        costs.put(DefaultFun.Bls12_381_G1_equal, pair(readConst(values, c), readConst(values, c)));
        // 203-205: Bls12_381_G1_hashToGroup — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.Bls12_381_G1_hashToGroup, pair(readLinearInX(values, c), readConst(values, c)));
        // 206-207: Bls12_381_G1_neg
        costs.put(DefaultFun.Bls12_381_G1_neg, pair(readConst(values, c), readConst(values, c)));
        // 208-210: Bls12_381_G1_scalarMul — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.Bls12_381_G1_scalarMul, pair(readLinearInX(values, c), readConst(values, c)));
        // 211-212: Bls12_381_G1_uncompress
        costs.put(DefaultFun.Bls12_381_G1_uncompress, pair(readConst(values, c), readConst(values, c)));
        // 213-214: Bls12_381_G2_add
        costs.put(DefaultFun.Bls12_381_G2_add, pair(readConst(values, c), readConst(values, c)));
        // 215-216: Bls12_381_G2_compress
        costs.put(DefaultFun.Bls12_381_G2_compress, pair(readConst(values, c), readConst(values, c)));
        // 217-218: Bls12_381_G2_equal
        costs.put(DefaultFun.Bls12_381_G2_equal, pair(readConst(values, c), readConst(values, c)));
        // 219-221: Bls12_381_G2_hashToGroup — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.Bls12_381_G2_hashToGroup, pair(readLinearInX(values, c), readConst(values, c)));
        // 222-223: Bls12_381_G2_neg
        costs.put(DefaultFun.Bls12_381_G2_neg, pair(readConst(values, c), readConst(values, c)));
        // 224-226: Bls12_381_G2_scalarMul — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.Bls12_381_G2_scalarMul, pair(readLinearInX(values, c), readConst(values, c)));
        // 227-228: Bls12_381_G2_uncompress
        costs.put(DefaultFun.Bls12_381_G2_uncompress, pair(readConst(values, c), readConst(values, c)));
        // 229-230: Bls12_381_finalVerify
        costs.put(DefaultFun.Bls12_381_finalVerify, pair(readConst(values, c), readConst(values, c)));
        // 231-232: Bls12_381_millerLoop
        costs.put(DefaultFun.Bls12_381_millerLoop, pair(readConst(values, c), readConst(values, c)));
        // 233-234: Bls12_381_mulMlResult
        costs.put(DefaultFun.Bls12_381_mulMlResult, pair(readConst(values, c), readConst(values, c)));
        // 235-237: Keccak_256 — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.Keccak_256, pair(readLinearInX(values, c), readConst(values, c)));
        // 238-240: Blake2b_224 — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.Blake2b_224, pair(readLinearInX(values, c), readConst(values, c)));
        // 241-245: IntegerToByteString — QuadraticInZ(cpu) + LiteralInYOrLinearInZ(mem)
        costs.put(DefaultFun.IntegerToByteString, pair(readQuadraticInZ(values, c), readLiteralInYOrLinearInZ(values, c)));
        // 246-250: ByteStringToInteger — QuadraticInY(cpu) + LinearInY(mem)
        costs.put(DefaultFun.ByteStringToInteger, pair(readQuadraticInY(values, c), readLinearInY(values, c)));

        // === Plomin / PV10 bitwise builtins (indices 251–296) ===
        // 251-255: AndByteString — LinearInYAndZ(cpu) + LinearInMaxYZ(mem)
        costs.put(DefaultFun.AndByteString, pair(readLinearInYAndZ(values, c), readLinearInMaxYZ(values, c)));
        // 256-260: OrByteString
        costs.put(DefaultFun.OrByteString, pair(readLinearInYAndZ(values, c), readLinearInMaxYZ(values, c)));
        // 261-265: XorByteString
        costs.put(DefaultFun.XorByteString, pair(readLinearInYAndZ(values, c), readLinearInMaxYZ(values, c)));
        // 266-269: ComplementByteString — LinearInX(cpu) + LinearInX(mem)
        costs.put(DefaultFun.ComplementByteString, pair(readLinearInX(values, c), readLinearInX(values, c)));
        // 270-271: ReadBit
        costs.put(DefaultFun.ReadBit, pair(readConst(values, c), readConst(values, c)));
        // 272-275: WriteBits — LinearInY(cpu) + LinearInX(mem)
        costs.put(DefaultFun.WriteBits, pair(readLinearInY(values, c), readLinearInX(values, c)));
        // 276-279: ReplicateByte — LinearInX(cpu) + LinearInX(mem)
        costs.put(DefaultFun.ReplicateByte, pair(readLinearInX(values, c), readLinearInX(values, c)));
        // 280-283: ShiftByteString — LinearInX(cpu) + LinearInX(mem)
        costs.put(DefaultFun.ShiftByteString, pair(readLinearInX(values, c), readLinearInX(values, c)));
        // 284-287: RotateByteString — LinearInX(cpu) + LinearInX(mem)
        costs.put(DefaultFun.RotateByteString, pair(readLinearInX(values, c), readLinearInX(values, c)));
        // 288-290: CountSetBits — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.CountSetBits, pair(readLinearInX(values, c), readConst(values, c)));
        // 291-293: FindFirstSetBit — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.FindFirstSetBit, pair(readLinearInX(values, c), readConst(values, c)));
        // 294-296: Ripemd_160 — LinearInX(cpu) + Const(mem)
        costs.put(DefaultFun.Ripemd_160, pair(readLinearInX(values, c), readConst(values, c)));

        assert c[0] == PV10_PARAM_COUNT : "Parser consumed " + c[0] + " params, expected " + PV10_PARAM_COUNT;

        // === PV11 builtins (indices 297–349, if present) ===
        if (protocolMajorVersion >= 11 && values.length >= PV11_PARAM_COUNT) {
            // 297-301: ExpModInteger — ExpModCost(cpu) + LinearInZ(mem)
            costs.put(DefaultFun.ExpModInteger, pair(readExpModCost(values, c), readLinearInZ(values, c)));
            // 302-304: DropList — LinearInX(cpu) + Const(mem)
            costs.put(DefaultFun.DropList, pair(readLinearInX(values, c), readConst(values, c)));
            // 305-306: LengthOfArray — Const(cpu) + Const(mem)
            costs.put(DefaultFun.LengthOfArray, pair(readConst(values, c), readConst(values, c)));
            // 307-310: ListToArray — LinearInX(cpu) + LinearInX(mem)
            costs.put(DefaultFun.ListToArray, pair(readLinearInX(values, c), readLinearInX(values, c)));
            // 311-312: IndexArray — Const(cpu) + Const(mem)
            costs.put(DefaultFun.IndexArray, pair(readConst(values, c), readConst(values, c)));
            // 313-315: Bls12_381_G1_multiScalarMul — LinearInX(cpu) + Const(mem)
            costs.put(DefaultFun.Bls12_381_G1_multiScalarMul, pair(readLinearInX(values, c), readConst(values, c)));
            // 316-318: Bls12_381_G2_multiScalarMul — LinearInX(cpu) + Const(mem)
            costs.put(DefaultFun.Bls12_381_G2_multiScalarMul, pair(readLinearInX(values, c), readConst(values, c)));
            // 319-322: InsertCoin — LinearInU(cpu) + LinearInU(mem)
            costs.put(DefaultFun.InsertCoin, pair(readLinearInU(values, c), readLinearInU(values, c)));
            // 323-325: LookupCoin — LinearInZ(cpu) + Const(mem)
            costs.put(DefaultFun.LookupCoin, pair(readLinearInZ(values, c), readConst(values, c)));
            // 326-331: UnionValue — WithInteractionInXAndY(cpu) + AddedSizes(mem)
            costs.put(DefaultFun.UnionValue, pair(readWithInteraction(values, c), readAddedSizes(values, c)));
            // 332-336: ValueContains — ConstAboveDiagLinear(cpu) + Const(mem)
            costs.put(DefaultFun.ValueContains, pair(readConstAboveDiagLinear(values, c), readConst(values, c)));
            // 337-340: ValueData — LinearInX(cpu) + LinearInX(mem)
            costs.put(DefaultFun.ValueData, pair(readLinearInX(values, c), readLinearInX(values, c)));
            // 341-345: UnValueData — QuadraticInX(cpu) + LinearInX(mem)
            costs.put(DefaultFun.UnValueData, pair(readQuadraticInX(values, c), readLinearInX(values, c)));
            // 346-349: ScaleValue — LinearInY(cpu) + LinearInY(mem)
            costs.put(DefaultFun.ScaleValue, pair(readLinearInY(values, c), readLinearInY(values, c)));

            assert c[0] == PV11_PARAM_COUNT : "PV11 parser consumed " + c[0] + " params, expected " + PV11_PARAM_COUNT;
        }

        // Build MachineCosts
        MachineCosts mc = new MachineCosts(
                startupCpu, startupMem,
                varCpu, varMem,
                lamCpu, lamMem,
                applyCpu, applyMem,
                forceCpu, forceMem,
                delayCpu, delayMem,
                constCpu, constMem,
                builtinCpu, builtinMem,
                constrCpu, constrMem,
                caseCpu, caseMem
        );

        return new ParsedCostModel(mc, new BuiltinCostModel(costs));
    }

    /**
     * Build a flat cost model parameter array from the default cost model (PV10).
     * Useful for testing round-trip parsing.
     *
     * @return the flat array in canonical PV10 order (297 elements)
     */
    public static long[] defaultToFlatArray() {
        return defaultToFlatArray(10);
    }

    /**
     * Build a flat cost model parameter array from the default cost model
     * for the specified protocol version.
     *
     * @param protocolMajorVersion the protocol major version (10 for PV10, 11+ for PV11)
     * @return the flat array (297 elements for PV10, 350 for PV11)
     */
    public static long[] defaultToFlatArray(int protocolMajorVersion) {
        MachineCosts mc = DefaultCostModel.defaultMachineCosts();
        BuiltinCostModel bcm = DefaultCostModel.defaultBuiltinCostModel();
        return toFlatArray(mc, bcm, protocolMajorVersion);
    }

    /**
     * Build a flat cost model parameter array from the given cost model (PV10).
     *
     * @return the flat array in canonical PV10 order (297 elements)
     */
    public static long[] toFlatArray(MachineCosts mc, BuiltinCostModel bcm) {
        return toFlatArray(mc, bcm, 10);
    }

    /**
     * Build a flat cost model parameter array from the given cost model.
     *
     * @param protocolMajorVersion the protocol major version
     * @return the flat array (297 elements for PV10, 350 for PV11+)
     */
    public static long[] toFlatArray(MachineCosts mc, BuiltinCostModel bcm, int protocolMajorVersion) {
        int paramCount = protocolMajorVersion >= 11 ? PV11_PARAM_COUNT : PV10_PARAM_COUNT;
        long[] values = new long[paramCount];
        int[] c = {0};

        // V1/V2 builtins
        writeParams(values, c, bcm.get(DefaultFun.AddInteger));
        writeParams(values, c, bcm.get(DefaultFun.AppendByteString));
        writeParams(values, c, bcm.get(DefaultFun.AppendString));
        writeParams(values, c, bcm.get(DefaultFun.BData));
        writeParams(values, c, bcm.get(DefaultFun.Blake2b_256));

        // V1/V2 Machine costs (alphabetical: apply, builtin, const, delay, force, lam, startup, var)
        values[c[0]++] = mc.applyCpu();    values[c[0]++] = mc.applyMem();
        values[c[0]++] = mc.builtinCpu();  values[c[0]++] = mc.builtinMem();
        values[c[0]++] = mc.constCpu();    values[c[0]++] = mc.constMem();
        values[c[0]++] = mc.delayCpu();    values[c[0]++] = mc.delayMem();
        values[c[0]++] = mc.forceCpu();    values[c[0]++] = mc.forceMem();
        values[c[0]++] = mc.lamCpu();      values[c[0]++] = mc.lamMem();
        values[c[0]++] = mc.startupCpu();  values[c[0]++] = mc.startupMem();
        values[c[0]++] = mc.varCpu();      values[c[0]++] = mc.varMem();

        // V1/V2 builtins continued
        writeParams(values, c, bcm.get(DefaultFun.ChooseData));
        writeParams(values, c, bcm.get(DefaultFun.ChooseList));
        writeParams(values, c, bcm.get(DefaultFun.ChooseUnit));
        writeParams(values, c, bcm.get(DefaultFun.ConsByteString));
        writeParams(values, c, bcm.get(DefaultFun.ConstrData));
        writeParams(values, c, bcm.get(DefaultFun.DecodeUtf8));
        writeDivisionParams(values, c, bcm.get(DefaultFun.DivideInteger), true);
        writeParams(values, c, bcm.get(DefaultFun.EncodeUtf8));
        writeParams(values, c, bcm.get(DefaultFun.EqualsByteString));
        writeParams(values, c, bcm.get(DefaultFun.EqualsData));
        writeParams(values, c, bcm.get(DefaultFun.EqualsInteger));
        writeParams(values, c, bcm.get(DefaultFun.EqualsString));
        writeParams(values, c, bcm.get(DefaultFun.FstPair));
        writeParams(values, c, bcm.get(DefaultFun.HeadList));
        writeParams(values, c, bcm.get(DefaultFun.IData));
        writeParams(values, c, bcm.get(DefaultFun.IfThenElse));
        writeParams(values, c, bcm.get(DefaultFun.IndexByteString));
        writeParams(values, c, bcm.get(DefaultFun.LengthOfByteString));
        writeParams(values, c, bcm.get(DefaultFun.LessThanByteString));
        writeParams(values, c, bcm.get(DefaultFun.LessThanEqualsByteString));
        writeParams(values, c, bcm.get(DefaultFun.LessThanEqualsInteger));
        writeParams(values, c, bcm.get(DefaultFun.LessThanInteger));
        writeParams(values, c, bcm.get(DefaultFun.ListData));
        writeParams(values, c, bcm.get(DefaultFun.MapData));
        writeParams(values, c, bcm.get(DefaultFun.MkCons));
        writeParams(values, c, bcm.get(DefaultFun.MkNilData));
        writeParams(values, c, bcm.get(DefaultFun.MkNilPairData));
        writeParams(values, c, bcm.get(DefaultFun.MkPairData));
        writeDivisionParams(values, c, bcm.get(DefaultFun.ModInteger), false);
        writeParams(values, c, bcm.get(DefaultFun.MultiplyInteger));
        writeParams(values, c, bcm.get(DefaultFun.NullList));
        writeDivisionParams(values, c, bcm.get(DefaultFun.QuotientInteger), true);
        writeDivisionParams(values, c, bcm.get(DefaultFun.RemainderInteger), false);
        writeParams(values, c, bcm.get(DefaultFun.SerialiseData));
        writeParams(values, c, bcm.get(DefaultFun.Sha2_256));
        writeParams(values, c, bcm.get(DefaultFun.Sha3_256));
        writeParams(values, c, bcm.get(DefaultFun.SliceByteString));
        writeParams(values, c, bcm.get(DefaultFun.SndPair));
        writeParams(values, c, bcm.get(DefaultFun.SubtractInteger));
        writeParams(values, c, bcm.get(DefaultFun.TailList));
        writeParams(values, c, bcm.get(DefaultFun.Trace));
        writeParams(values, c, bcm.get(DefaultFun.UnBData));
        writeParams(values, c, bcm.get(DefaultFun.UnConstrData));
        writeParams(values, c, bcm.get(DefaultFun.UnIData));
        writeParams(values, c, bcm.get(DefaultFun.UnListData));
        writeParams(values, c, bcm.get(DefaultFun.UnMapData));
        writeParams(values, c, bcm.get(DefaultFun.VerifyEcdsaSecp256k1Signature));
        writeParams(values, c, bcm.get(DefaultFun.VerifyEd25519Signature));
        writeParams(values, c, bcm.get(DefaultFun.VerifySchnorrSecp256k1Signature));

        // V3 Machine costs (constr, case)
        values[c[0]++] = mc.constrCpu();   values[c[0]++] = mc.constrMem();
        values[c[0]++] = mc.caseCpu();     values[c[0]++] = mc.caseMem();

        // V3 BLS + crypto + conversions
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G1_add));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G1_compress));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G1_equal));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G1_hashToGroup));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G1_neg));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G1_scalarMul));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G1_uncompress));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G2_add));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G2_compress));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G2_equal));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G2_hashToGroup));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G2_neg));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G2_scalarMul));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G2_uncompress));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_finalVerify));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_millerLoop));
        writeParams(values, c, bcm.get(DefaultFun.Bls12_381_mulMlResult));
        writeParams(values, c, bcm.get(DefaultFun.Keccak_256));
        writeParams(values, c, bcm.get(DefaultFun.Blake2b_224));
        writeParams(values, c, bcm.get(DefaultFun.IntegerToByteString));
        writeParams(values, c, bcm.get(DefaultFun.ByteStringToInteger));

        // Plomin bitwise builtins
        writeParams(values, c, bcm.get(DefaultFun.AndByteString));
        writeParams(values, c, bcm.get(DefaultFun.OrByteString));
        writeParams(values, c, bcm.get(DefaultFun.XorByteString));
        writeParams(values, c, bcm.get(DefaultFun.ComplementByteString));
        writeParams(values, c, bcm.get(DefaultFun.ReadBit));
        writeParams(values, c, bcm.get(DefaultFun.WriteBits));
        writeParams(values, c, bcm.get(DefaultFun.ReplicateByte));
        writeParams(values, c, bcm.get(DefaultFun.ShiftByteString));
        writeParams(values, c, bcm.get(DefaultFun.RotateByteString));
        writeParams(values, c, bcm.get(DefaultFun.CountSetBits));
        writeParams(values, c, bcm.get(DefaultFun.FindFirstSetBit));
        writeParams(values, c, bcm.get(DefaultFun.Ripemd_160));

        assert c[0] == PV10_PARAM_COUNT : "Writer produced " + c[0] + " params, expected " + PV10_PARAM_COUNT;

        // === PV11 builtins (indices 297–349) ===
        if (protocolMajorVersion >= 11) {
            writeParams(values, c, bcm.get(DefaultFun.ExpModInteger));
            writeParams(values, c, bcm.get(DefaultFun.DropList));
            writeParams(values, c, bcm.get(DefaultFun.LengthOfArray));
            writeParams(values, c, bcm.get(DefaultFun.ListToArray));
            writeParams(values, c, bcm.get(DefaultFun.IndexArray));
            writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G1_multiScalarMul));
            writeParams(values, c, bcm.get(DefaultFun.Bls12_381_G2_multiScalarMul));
            writeParams(values, c, bcm.get(DefaultFun.InsertCoin));
            writeParams(values, c, bcm.get(DefaultFun.LookupCoin));
            writeParams(values, c, bcm.get(DefaultFun.UnionValue));
            writeParams(values, c, bcm.get(DefaultFun.ValueContains));
            writeParams(values, c, bcm.get(DefaultFun.ValueData));
            writeParams(values, c, bcm.get(DefaultFun.UnValueData));
            writeParams(values, c, bcm.get(DefaultFun.ScaleValue));

            assert c[0] == PV11_PARAM_COUNT : "PV11 writer produced " + c[0] + " params, expected " + PV11_PARAM_COUNT;
        }

        return values;
    }

    // ========== Read helpers (array → CostFunction) ==========

    private static long next(long[] v, int[] c) {
        return v[c[0]++];
    }

    private static CostFunction readConst(long[] v, int[] c) {
        return new ConstantCost(next(v, c));
    }

    private static CostFunction readLinearInX(long[] v, int[] c) {
        return new LinearInX(next(v, c), next(v, c));
    }

    private static CostFunction readLinearInY(long[] v, int[] c) {
        return new LinearInY(next(v, c), next(v, c));
    }

    private static CostFunction readLinearInZ(long[] v, int[] c) {
        return new LinearInZ(next(v, c), next(v, c));
    }

    private static CostFunction readAddedSizes(long[] v, int[] c) {
        return new AddedSizes(next(v, c), next(v, c));
    }

    private static CostFunction readMultipliedSizes(long[] v, int[] c) {
        return new MultipliedSizes(next(v, c), next(v, c));
    }

    private static CostFunction readMinSize(long[] v, int[] c) {
        return new MinSize(next(v, c), next(v, c));
    }

    private static CostFunction readMaxSize(long[] v, int[] c) {
        return new MaxSize(next(v, c), next(v, c));
    }

    /**
     * Read SubtractedSizes. Array order: intercept, minimum, slope.
     * Constructor order: intercept, slope, minimum — needs swap.
     */
    private static CostFunction readSubtractedSizes(long[] v, int[] c) {
        long intercept = next(v, c);
        long minimum = next(v, c);
        long slope = next(v, c);
        return new SubtractedSizes(intercept, slope, minimum);
    }

    private static CostFunction readConstAboveDiag(long[] v, int[] c) {
        return new ConstAboveDiagonal(
                next(v, c), next(v, c), next(v, c), next(v, c),
                next(v, c), next(v, c), next(v, c), next(v, c));
    }

    private static CostFunction readLinearOnDiag(long[] v, int[] c) {
        return new LinearOnDiagonal(next(v, c), next(v, c), next(v, c));
    }

    private static CostFunction readQuadraticInY(long[] v, int[] c) {
        return new QuadraticInY(next(v, c), next(v, c), next(v, c));
    }

    private static CostFunction readQuadraticInZ(long[] v, int[] c) {
        return new QuadraticInZ(next(v, c), next(v, c), next(v, c));
    }

    private static CostFunction readLiteralInYOrLinearInZ(long[] v, int[] c) {
        return new LiteralInYOrLinearInZ(next(v, c), next(v, c));
    }

    private static CostFunction readLinearInMaxYZ(long[] v, int[] c) {
        return new LinearInMaxYZ(next(v, c), next(v, c));
    }

    private static CostFunction readLinearInYAndZ(long[] v, int[] c) {
        return new LinearInYAndZ(next(v, c), next(v, c), next(v, c));
    }

    private static CostFunction readExpModCost(long[] v, int[] c) {
        return new ExpModCost(next(v, c), next(v, c), next(v, c));
    }

    private static CostFunction readLinearInU(long[] v, int[] c) {
        return new LinearInU(next(v, c), next(v, c));
    }

    private static CostFunction readQuadraticInX(long[] v, int[] c) {
        return new QuadraticInX(next(v, c), next(v, c), next(v, c));
    }

    private static CostFunction readWithInteraction(long[] v, int[] c) {
        return new WithInteractionInXAndY(next(v, c), next(v, c), next(v, c), next(v, c));
    }

    private static CostFunction readConstAboveDiagLinear(long[] v, int[] c) {
        return new ConstAboveDiagonalLinear(next(v, c), next(v, c), next(v, c), next(v, c));
    }

    // ========== Write helpers (CostFunction → array) ==========

    private static void writeParams(long[] values, int[] c, BuiltinCostModel.CostPair pair) {
        writeCostFunction(values, c, pair.cpu());
        writeCostFunction(values, c, pair.mem());
    }

    /**
     * Write params for division builtins (DivideInteger, QuotientInteger, ModInteger, RemainderInteger).
     * These have ConstAboveDiagonal CPU and either SubtractedSizes or LinearInY memory.
     * SubtractedSizes array order: intercept, minimum, slope (swapped from constructor).
     */
    private static void writeDivisionParams(long[] values, int[] c, BuiltinCostModel.CostPair pair,
                                            boolean memIsSubtractedSizes) {
        writeCostFunction(values, c, pair.cpu());
        if (memIsSubtractedSizes && pair.mem() instanceof SubtractedSizes ss) {
            values[c[0]++] = ss.intercept();
            values[c[0]++] = ss.minimum();
            values[c[0]++] = ss.slope();
        } else {
            writeCostFunction(values, c, pair.mem());
        }
    }

    private static void writeCostFunction(long[] values, int[] c, CostFunction cf) {
        switch (cf) {
            case ConstantCost cc -> values[c[0]++] = cc.cost();
            case LinearInX li -> { values[c[0]++] = li.intercept(); values[c[0]++] = li.slope(); }
            case LinearInY li -> { values[c[0]++] = li.intercept(); values[c[0]++] = li.slope(); }
            case LinearInZ li -> { values[c[0]++] = li.intercept(); values[c[0]++] = li.slope(); }
            case AddedSizes as -> { values[c[0]++] = as.intercept(); values[c[0]++] = as.slope(); }
            case MultipliedSizes ms -> { values[c[0]++] = ms.intercept(); values[c[0]++] = ms.slope(); }
            case MinSize ms -> { values[c[0]++] = ms.intercept(); values[c[0]++] = ms.slope(); }
            case MaxSize ms -> { values[c[0]++] = ms.intercept(); values[c[0]++] = ms.slope(); }
            case SubtractedSizes ss -> {
                // Default write order: intercept, slope, minimum (constructor order)
                // Division builtins use writeDivisionParams for the swapped order
                values[c[0]++] = ss.intercept();
                values[c[0]++] = ss.slope();
                values[c[0]++] = ss.minimum();
            }
            case ConstAboveDiagonal ca -> {
                values[c[0]++] = ca.constant(); values[c[0]++] = ca.c00();
                values[c[0]++] = ca.c01();      values[c[0]++] = ca.c02();
                values[c[0]++] = ca.c10();      values[c[0]++] = ca.c11();
                values[c[0]++] = ca.c20();      values[c[0]++] = ca.minimum();
            }
            case LinearOnDiagonal ld -> {
                values[c[0]++] = ld.constant(); values[c[0]++] = ld.intercept(); values[c[0]++] = ld.slope();
            }
            case QuadraticInY q -> { values[c[0]++] = q.c0(); values[c[0]++] = q.c1(); values[c[0]++] = q.c2(); }
            case QuadraticInZ q -> { values[c[0]++] = q.c0(); values[c[0]++] = q.c1(); values[c[0]++] = q.c2(); }
            case LiteralInYOrLinearInZ li -> { values[c[0]++] = li.intercept(); values[c[0]++] = li.slope(); }
            case LinearInMaxYZ lm -> { values[c[0]++] = lm.intercept(); values[c[0]++] = lm.slope(); }
            case LinearInYAndZ lyz -> {
                values[c[0]++] = lyz.intercept(); values[c[0]++] = lyz.slope1(); values[c[0]++] = lyz.slope2();
            }
            case ExpModCost em -> { values[c[0]++] = em.c00(); values[c[0]++] = em.c11(); values[c[0]++] = em.c12(); }
            case LinearInU li -> { values[c[0]++] = li.intercept(); values[c[0]++] = li.slope(); }
            case QuadraticInX q -> { values[c[0]++] = q.c0(); values[c[0]++] = q.c1(); values[c[0]++] = q.c2(); }
            case ConstAboveDiagonalLinear ca -> {
                values[c[0]++] = ca.constant(); values[c[0]++] = ca.intercept();
                values[c[0]++] = ca.slope1(); values[c[0]++] = ca.slope2();
            }
            case WithInteractionInXAndY wi -> {
                values[c[0]++] = wi.c00(); values[c[0]++] = wi.c10();
                values[c[0]++] = wi.c01(); values[c[0]++] = wi.c11();
            }
        }
    }

    private static BuiltinCostModel.CostPair pair(CostFunction cpu, CostFunction mem) {
        return new BuiltinCostModel.CostPair(cpu, mem);
    }
}
