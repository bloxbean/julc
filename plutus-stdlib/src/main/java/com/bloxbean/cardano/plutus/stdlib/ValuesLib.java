package com.bloxbean.cardano.plutus.stdlib;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;

import java.math.BigInteger;
import java.util.List;

/**
 * Value manipulation operations built as PIR term builders.
 * <p>
 * In Plutus, a Value is represented as:
 * {@code Map<ByteString, Map<ByteString, Integer>>}
 * (i.e., a map from currency symbol to a map from token name to amount).
 * <p>
 * Lovelace (ADA) is stored under the empty bytestring currency symbol
 * and empty bytestring token name.
 */
public final class ValuesLib {

    private ValuesLib() {}

    /**
     * Extracts the lovelace (ADA) amount from a Value.
     * <p>
     * Value is encoded as Data:
     * {@code Map[ (B"", Map[ (B"", I(amount)) ]) ]}
     * <p>
     * Implementation:
     * <ol>
     *   <li>UnMapData(value) to get list of pairs</li>
     *   <li>Find the entry where FstPair == B"" (empty bytestring)</li>
     *   <li>UnMapData(SndPair(entry)) to get inner map</li>
     *   <li>Find the entry where FstPair == B"" (empty bytestring)</li>
     *   <li>UnIData(SndPair(innerEntry)) to get the integer amount</li>
     * </ol>
     * <p>
     * For simplicity, assumes the empty-bytestring currency symbol (lovelace) is
     * the first entry in the outer map. This is the standard representation.
     *
     * @param value PIR term representing a Value (as Data)
     * @return PIR term that evaluates to Integer (lovelace amount)
     */
    /**
     * Checks if value a is greater than or equal to value b (by lovelace amount).
     * <p>
     * This is a simplified comparison that only checks the ADA/lovelace amount.
     * For a full multi-asset comparison, each policy/token pair would need to be checked.
     *
     * @param a PIR term representing a Value (as Data)
     * @param b PIR term representing a Value (as Data)
     * @return PIR term that evaluates to Bool (true if lovelaceOf(a) >= lovelaceOf(b))
     */
    public static PirTerm geq(PirTerm a, PirTerm b) {
        var aVar = new PirTerm.Var("a_val", new PirType.IntegerType());
        var bVar = new PirTerm.Var("b_val", new PirType.IntegerType());
        var cmp = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.LessThanEqualsInteger), bVar),
                aVar);
        return new PirTerm.Let("a_val", lovelaceOf(a),
                new PirTerm.Let("b_val", lovelaceOf(b), cmp));
    }

    /**
     * Extracts the amount of a specific asset from a Value.
     * <p>
     * Looks up the given policy ID (currency symbol) and token name in the Value map.
     * Returns 0 if the policy/token is not found.
     * <p>
     * Uses recursive search through both the outer (policy) and inner (token) maps.
     *
     * @param value      PIR term representing a Value (as Data)
     * @param policyId   PIR term representing a PolicyId (ByteString)
     * @param tokenName  PIR term representing a TokenName (ByteString)
     * @return PIR term that evaluates to Integer (the asset amount, or 0 if not found)
     */
    public static PirTerm assetOf(PirTerm value, PirTerm policyId, PirTerm tokenName) {
        // 1. UnMapData(value) -> list of (policy, tokenMap) pairs
        // 2. Search for matching policy using EqualsByteString on UnBData(FstPair)
        // 3. If found, UnMapData(SndPair(entry)) -> list of (tokenName, amount) pairs
        // 4. Search for matching tokenName
        // 5. If found, UnIData(SndPair(innerEntry))
        // 6. If not found at any step, return 0

        var valueVar = new PirTerm.Var("v_", new PirType.DataType());
        var policyVar = new PirTerm.Var("pol_", new PirType.ByteStringType());
        var tokenVar = new PirTerm.Var("tok_", new PirType.ByteStringType());

        // Inner search: find token in a token map
        var innerListVar = new PirTerm.Var("iList_", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var innerGoVar = new PirTerm.Var("innerGo_", new PirType.FunType(
                new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirType.IntegerType()));

        var innerNull = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), innerListVar);
        var innerHead = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), innerListVar);
        var innerTail = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), innerListVar);
        var innerKey = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), innerHead));
        var innerMatch = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsByteString), tokenVar),
                innerKey);
        var innerVal = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), innerHead));
        var innerRecurse = new PirTerm.App(innerGoVar, innerTail);
        var innerBody = new PirTerm.IfThenElse(innerNull,
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)),
                new PirTerm.IfThenElse(innerMatch, innerVal, innerRecurse));
        var innerGoBody = new PirTerm.Lam("iList_", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())), innerBody);

        // Outer search: find policy in value map
        var outerListVar = new PirTerm.Var("oList_", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var outerGoVar = new PirTerm.Var("outerGo_", new PirType.FunType(
                new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirType.IntegerType()));

        var outerNull = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), outerListVar);
        var outerHead = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), outerListVar);
        var outerTail = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), outerListVar);
        var outerKey = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), outerHead));
        var outerMatch = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsByteString), policyVar),
                outerKey);
        // If policy matches, search inner map
        var innerMapPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), outerHead));
        var innerSearch = new PirTerm.LetRec(
                List.of(new PirTerm.Binding("innerGo_", innerGoBody)),
                new PirTerm.App(innerGoVar, innerMapPairs));
        var outerRecurse = new PirTerm.App(outerGoVar, outerTail);
        var outerBody = new PirTerm.IfThenElse(outerNull,
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)),
                new PirTerm.IfThenElse(outerMatch, innerSearch, outerRecurse));
        var outerGoBody = new PirTerm.Lam("oList_", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())), outerBody);

        var outerMapPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), valueVar);

        return new PirTerm.Let("v_", value,
                new PirTerm.Let("pol_", policyId,
                        new PirTerm.Let("tok_", tokenName,
                                new PirTerm.LetRec(
                                        List.of(new PirTerm.Binding("outerGo_", outerGoBody)),
                                        new PirTerm.App(outerGoVar, outerMapPairs)))));
    }

    /**
     * Extracts the lovelace (ADA) amount from a Value.
     * <p>
     * Value is encoded as Data:
     * {@code Map[ (B"", Map[ (B"", I(amount)) ]) ]}
     * <p>
     * Implementation:
     * <ol>
     *   <li>UnMapData(value) to get list of pairs</li>
     *   <li>Find the entry where FstPair == B"" (empty bytestring)</li>
     *   <li>UnMapData(SndPair(entry)) to get inner map</li>
     *   <li>Find the entry where FstPair == B"" (empty bytestring)</li>
     *   <li>UnIData(SndPair(innerEntry)) to get the integer amount</li>
     * </ol>
     * <p>
     * For simplicity, assumes the empty-bytestring currency symbol (lovelace) is
     * the first entry in the outer map. This is the standard representation.
     *
     * @param value PIR term representing a Value (as Data)
     * @return PIR term that evaluates to Integer (lovelace amount)
     */
    public static PirTerm lovelaceOf(PirTerm value) {
        // lovelaceOf(value) =
        //   let pairs = UnMapData(value)                  -- list of (Data, Data) pairs
        //   let firstPair = HeadList(pairs)               -- first (currencySymbol, tokenMap) pair
        //   let tokenMapData = SndPair(firstPair)         -- Data encoding of the inner map
        //   let tokenPairs = UnMapData(tokenMapData)      -- list of (Data, Data) pairs
        //   let firstTokenPair = HeadList(tokenPairs)     -- first (tokenName, amount) pair
        //   let amountData = SndPair(firstTokenPair)      -- Data encoding of the amount
        //   in UnIData(amountData)                        -- Integer

        var pairsExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), value);

        var pairsVar = new PirTerm.Var("pairs", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));

        var firstPairExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), pairsVar);
        var firstPairVar = new PirTerm.Var("firstPair",
                new PirType.PairType(new PirType.DataType(), new PirType.DataType()));

        var tokenMapDataExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), firstPairVar);
        var tokenMapDataVar = new PirTerm.Var("tokenMapData", new PirType.DataType());

        var tokenPairsExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), tokenMapDataVar);
        var tokenPairsVar = new PirTerm.Var("tokenPairs", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));

        var firstTokenPairExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), tokenPairsVar);
        var firstTokenPairVar = new PirTerm.Var("firstTokenPair",
                new PirType.PairType(new PirType.DataType(), new PirType.DataType()));

        var amountDataExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), firstTokenPairVar);
        var amountDataVar = new PirTerm.Var("amountData", new PirType.DataType());

        var result = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), amountDataVar);

        // Build nested lets from inside out
        return new PirTerm.Let("pairs", pairsExpr,
                new PirTerm.Let("firstPair", firstPairExpr,
                        new PirTerm.Let("tokenMapData", tokenMapDataExpr,
                                new PirTerm.Let("tokenPairs", tokenPairsExpr,
                                        new PirTerm.Let("firstTokenPair", firstTokenPairExpr,
                                                new PirTerm.Let("amountData", amountDataExpr,
                                                        result))))));
    }
}
