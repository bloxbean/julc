package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.DefaultFun;

import java.util.EnumMap;
import java.util.Map;

import static com.bloxbean.cardano.julc.vm.java.cost.CostFunction.*;

/**
 * Default Plutus V3 cost model parameters.
 * <p>
 * Values are from the Cardano mainnet Conway genesis / Plutus cost model specification
 * (builtinCostModelC.json + cekMachineCostsC.json from the Plutus repository).
 */
public final class DefaultCostModel {

    private DefaultCostModel() {}

    /** Default CEK machine step costs (Plutus V3 Conway era). */
    public static MachineCosts defaultMachineCosts() {
        return new MachineCosts(
                /* startupCpu */ 100,    /* startupMem */ 100,
                /* varCpu */     16000,  /* varMem */     100,
                /* lamCpu */     16000,  /* lamMem */     100,
                /* applyCpu */   16000,  /* applyMem */   100,
                /* forceCpu */   16000,  /* forceMem */   100,
                /* delayCpu */   16000,  /* delayMem */   100,
                /* constCpu */   16000,  /* constMem */   100,
                /* builtinCpu */ 16000,  /* builtinMem */ 100,
                /* constrCpu */  16000,  /* constrMem */  100,
                /* caseCpu */    16000,  /* caseMem */    100
        );
    }

    /** Default builtin cost model (Plutus V3 Conway era). */
    public static BuiltinCostModel defaultBuiltinCostModel() {
        Map<DefaultFun, BuiltinCostModel.CostPair> costs = new EnumMap<>(DefaultFun.class);

        // === Integer arithmetic ===
        costs.put(DefaultFun.AddInteger, pair(
                new MaxSize(100788, 420),
                new MaxSize(1, 1)));
        costs.put(DefaultFun.SubtractInteger, pair(
                new MaxSize(100788, 420),
                new MaxSize(1, 1)));
        costs.put(DefaultFun.MultiplyInteger, pair(
                new MultipliedSizes(90434, 519),
                new AddedSizes(0, 1)));
        costs.put(DefaultFun.DivideInteger, pair(
                new ConstAboveDiagonal(85848, 123203, 7305, -900, 1716, 549, 57, 85848),
                new SubtractedSizes(0, 1, 1)));
        costs.put(DefaultFun.QuotientInteger, pair(
                new ConstAboveDiagonal(85848, 123203, 7305, -900, 1716, 549, 57, 85848),
                new SubtractedSizes(0, 1, 1)));
        costs.put(DefaultFun.RemainderInteger, pair(
                new ConstAboveDiagonal(85848, 123203, 7305, -900, 1716, 549, 57, 85848),
                new LinearInY(0, 1)));
        costs.put(DefaultFun.ModInteger, pair(
                new ConstAboveDiagonal(85848, 123203, 7305, -900, 1716, 549, 57, 85848),
                new LinearInY(0, 1)));
        costs.put(DefaultFun.EqualsInteger, pair(
                new MinSize(51775, 558),
                new ConstantCost(1)));
        costs.put(DefaultFun.LessThanInteger, pair(
                new MinSize(44749, 541),
                new ConstantCost(1)));
        costs.put(DefaultFun.LessThanEqualsInteger, pair(
                new MinSize(43285, 552),
                new ConstantCost(1)));

        // === ByteString operations ===
        costs.put(DefaultFun.AppendByteString, pair(
                new AddedSizes(1000, 173),
                new AddedSizes(0, 1)));
        costs.put(DefaultFun.ConsByteString, pair(
                new LinearInY(72010, 178),
                new AddedSizes(0, 1)));
        costs.put(DefaultFun.SliceByteString, pair(
                new LinearInZ(20467, 1),
                new LinearInZ(4, 0)));
        costs.put(DefaultFun.LengthOfByteString, pair(
                new ConstantCost(22100),
                new ConstantCost(10)));
        costs.put(DefaultFun.IndexByteString, pair(
                new ConstantCost(13169),
                new ConstantCost(4)));
        costs.put(DefaultFun.EqualsByteString, pair(
                new LinearOnDiagonal(24548, 29498, 38),
                new ConstantCost(1)));
        costs.put(DefaultFun.LessThanByteString, pair(
                new MinSize(28999, 74),
                new ConstantCost(1)));
        costs.put(DefaultFun.LessThanEqualsByteString, pair(
                new MinSize(28999, 74),
                new ConstantCost(1)));

        // === Crypto ===
        costs.put(DefaultFun.Sha2_256, pair(
                new LinearInX(270652, 22588),
                new ConstantCost(4)));
        costs.put(DefaultFun.Sha3_256, pair(
                new LinearInX(1457325, 64566),
                new ConstantCost(4)));
        costs.put(DefaultFun.Blake2b_256, pair(
                new LinearInX(201305, 8356),
                new ConstantCost(4)));
        costs.put(DefaultFun.VerifyEd25519Signature, pair(
                new LinearInY(53384111, 14333),
                new ConstantCost(10)));

        // === String ===
        costs.put(DefaultFun.AppendString, pair(
                new AddedSizes(1000, 59957),
                new AddedSizes(4, 1)));
        costs.put(DefaultFun.EqualsString, pair(
                new LinearOnDiagonal(39184, 1000, 60594),
                new ConstantCost(1)));
        costs.put(DefaultFun.EncodeUtf8, pair(
                new LinearInX(1000, 42921),
                new LinearInX(4, 2)));
        costs.put(DefaultFun.DecodeUtf8, pair(
                new LinearInX(91189, 769),
                new LinearInX(4, 2)));

        // === Control ===
        costs.put(DefaultFun.IfThenElse, pair(
                new ConstantCost(76049),
                new ConstantCost(1)));
        costs.put(DefaultFun.ChooseUnit, pair(
                new ConstantCost(61462),
                new ConstantCost(4)));
        costs.put(DefaultFun.Trace, pair(
                new ConstantCost(59498),
                new ConstantCost(32)));

        // === Pair ===
        costs.put(DefaultFun.FstPair, pair(
                new ConstantCost(141895),
                new ConstantCost(32)));
        costs.put(DefaultFun.SndPair, pair(
                new ConstantCost(141992),
                new ConstantCost(32)));

        // === List ===
        costs.put(DefaultFun.ChooseList, pair(
                new ConstantCost(132994),
                new ConstantCost(32)));
        costs.put(DefaultFun.MkCons, pair(
                new ConstantCost(72362),
                new ConstantCost(32)));
        costs.put(DefaultFun.HeadList, pair(
                new ConstantCost(83150),
                new ConstantCost(32)));
        costs.put(DefaultFun.TailList, pair(
                new ConstantCost(81663),
                new ConstantCost(32)));
        costs.put(DefaultFun.NullList, pair(
                new ConstantCost(74433),
                new ConstantCost(32)));

        // === Data ===
        costs.put(DefaultFun.ChooseData, pair(
                new ConstantCost(94375),
                new ConstantCost(32)));
        costs.put(DefaultFun.ConstrData, pair(
                new ConstantCost(22151),
                new ConstantCost(32)));
        costs.put(DefaultFun.MapData, pair(
                new ConstantCost(68246),
                new ConstantCost(32)));
        costs.put(DefaultFun.ListData, pair(
                new ConstantCost(33852),
                new ConstantCost(32)));
        costs.put(DefaultFun.IData, pair(
                new ConstantCost(15299),
                new ConstantCost(32)));
        costs.put(DefaultFun.BData, pair(
                new ConstantCost(11183),
                new ConstantCost(32)));
        costs.put(DefaultFun.UnConstrData, pair(
                new ConstantCost(24588),
                new ConstantCost(32)));
        costs.put(DefaultFun.UnMapData, pair(
                new ConstantCost(24623),
                new ConstantCost(32)));
        costs.put(DefaultFun.UnListData, pair(
                new ConstantCost(25933),
                new ConstantCost(32)));
        costs.put(DefaultFun.UnIData, pair(
                new ConstantCost(20744),
                new ConstantCost(32)));
        costs.put(DefaultFun.UnBData, pair(
                new ConstantCost(20142),
                new ConstantCost(32)));
        costs.put(DefaultFun.EqualsData, pair(
                new MinSize(898148, 27279),
                new ConstantCost(1)));
        costs.put(DefaultFun.MkPairData, pair(
                new ConstantCost(11546),
                new ConstantCost(32)));
        costs.put(DefaultFun.MkNilData, pair(
                new ConstantCost(7243),
                new ConstantCost(32)));
        costs.put(DefaultFun.MkNilPairData, pair(
                new ConstantCost(7391),
                new ConstantCost(32)));

        // === V2 ===
        costs.put(DefaultFun.SerialiseData, pair(
                new LinearInX(955506, 213312),
                new LinearInX(0, 2)));
        costs.put(DefaultFun.VerifyEcdsaSecp256k1Signature, pair(
                new ConstantCost(43053543),
                new ConstantCost(10)));
        costs.put(DefaultFun.VerifySchnorrSecp256k1Signature, pair(
                new LinearInY(43574283, 26308),
                new ConstantCost(10)));

        // === V3 BLS12-381 ===
        costs.put(DefaultFun.Bls12_381_G1_add, pair(
                new ConstantCost(962335),
                new ConstantCost(18)));
        costs.put(DefaultFun.Bls12_381_G1_neg, pair(
                new ConstantCost(267929),
                new ConstantCost(18)));
        costs.put(DefaultFun.Bls12_381_G1_scalarMul, pair(
                new LinearInX(76433006, 8868),
                new ConstantCost(18)));
        costs.put(DefaultFun.Bls12_381_G1_equal, pair(
                new ConstantCost(442008),
                new ConstantCost(1)));
        costs.put(DefaultFun.Bls12_381_G1_compress, pair(
                new ConstantCost(2780678),
                new ConstantCost(6)));
        costs.put(DefaultFun.Bls12_381_G1_uncompress, pair(
                new ConstantCost(52948122),
                new ConstantCost(18)));
        costs.put(DefaultFun.Bls12_381_G1_hashToGroup, pair(
                new LinearInX(52538055, 3756),
                new ConstantCost(18)));
        costs.put(DefaultFun.Bls12_381_G2_add, pair(
                new ConstantCost(1995836),
                new ConstantCost(36)));
        costs.put(DefaultFun.Bls12_381_G2_neg, pair(
                new ConstantCost(284546),
                new ConstantCost(36)));
        costs.put(DefaultFun.Bls12_381_G2_scalarMul, pair(
                new LinearInX(158221314, 26549),
                new ConstantCost(36)));
        costs.put(DefaultFun.Bls12_381_G2_equal, pair(
                new ConstantCost(901022),
                new ConstantCost(1)));
        costs.put(DefaultFun.Bls12_381_G2_compress, pair(
                new ConstantCost(3227919),
                new ConstantCost(12)));
        costs.put(DefaultFun.Bls12_381_G2_uncompress, pair(
                new ConstantCost(74698472),
                new ConstantCost(36)));
        costs.put(DefaultFun.Bls12_381_G2_hashToGroup, pair(
                new LinearInX(166917843, 4307),
                new ConstantCost(36)));
        costs.put(DefaultFun.Bls12_381_millerLoop, pair(
                new ConstantCost(254006273),
                new ConstantCost(72)));
        costs.put(DefaultFun.Bls12_381_mulMlResult, pair(
                new ConstantCost(2174038),
                new ConstantCost(72)));
        costs.put(DefaultFun.Bls12_381_finalVerify, pair(
                new ConstantCost(333849714),
                new ConstantCost(1)));

        // === V3 Crypto ===
        costs.put(DefaultFun.Keccak_256, pair(
                new LinearInX(2261318, 64571),
                new ConstantCost(4)));
        costs.put(DefaultFun.Blake2b_224, pair(
                new LinearInX(207616, 8310),
                new ConstantCost(4)));

        // === V3 Integer/ByteString conversions (CIP-121) ===
        costs.put(DefaultFun.IntegerToByteString, pair(
                new QuadraticInZ(1293828, 28716, 63),
                new LiteralInYOrLinearInZ(0, 1)));
        costs.put(DefaultFun.ByteStringToInteger, pair(
                new QuadraticInY(1006041, 43623, 251),
                new LinearInY(0, 1)));

        // === V3 Bitwise logical (CIP-122) ===
        costs.put(DefaultFun.AndByteString, pair(
                new LinearInYAndZ(100181, 726, 719),
                new LinearInMaxYZ(0, 1)));
        costs.put(DefaultFun.OrByteString, pair(
                new LinearInYAndZ(100181, 726, 719),
                new LinearInMaxYZ(0, 1)));
        costs.put(DefaultFun.XorByteString, pair(
                new LinearInYAndZ(100181, 726, 719),
                new LinearInMaxYZ(0, 1)));
        costs.put(DefaultFun.ComplementByteString, pair(
                new LinearInX(107878, 680),
                new LinearInX(0, 1)));
        costs.put(DefaultFun.ReadBit, pair(
                new ConstantCost(95336),
                new ConstantCost(1)));
        costs.put(DefaultFun.WriteBits, pair(
                new LinearInY(281145, 18848),
                new LinearInX(0, 1)));
        costs.put(DefaultFun.ReplicateByte, pair(
                new LinearInX(180194, 159),
                new LinearInX(1, 1)));

        // === V3 Shift/Rotate (CIP-123) ===
        costs.put(DefaultFun.ShiftByteString, pair(
                new LinearInX(158519, 8942),
                new LinearInX(0, 1)));
        costs.put(DefaultFun.RotateByteString, pair(
                new LinearInX(159378, 8813),
                new LinearInX(0, 1)));
        costs.put(DefaultFun.CountSetBits, pair(
                new LinearInX(107490, 3298),
                new ConstantCost(1)));
        costs.put(DefaultFun.FindFirstSetBit, pair(
                new LinearInX(106057, 655),
                new ConstantCost(1)));

        // === V3 RIPEMD-160 (CIP-127) ===
        costs.put(DefaultFun.Ripemd_160, pair(
                new LinearInX(1964219, 24520),
                new ConstantCost(3)));

        // === V3 Modular exponentiation (CIP-109) ===
        costs.put(DefaultFun.ExpModInteger, pair(
                new ExpModCost(607153, 231697, 53144),
                new LinearInZ(0, 1)));

        return new BuiltinCostModel(costs);
    }

    private static BuiltinCostModel.CostPair pair(CostFunction cpu, CostFunction mem) {
        return new BuiltinCostModel.CostPair(cpu, mem);
    }
}
