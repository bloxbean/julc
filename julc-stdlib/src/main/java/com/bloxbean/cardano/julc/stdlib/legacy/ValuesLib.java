package com.bloxbean.cardano.julc.stdlib.legacy;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;

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

    // =========================================================================
    // New Value operations: multi-asset geq, leq, eq, isZero, singleton,
    //                        add, subtract, negate, flatten
    // =========================================================================

    /**
     * Checks if value a >= value b for ALL policy/token pairs (multi-asset).
     * <p>
     * For each (policy, token, amount) in b, checks that a contains at least
     * that amount for the same policy/token. Uses nested folds over the maps.
     *
     * @param a PIR term representing a Value (as Data)
     * @param b PIR term representing a Value (as Data)
     * @return PIR term that evaluates to Bool
     */
    public static PirTerm geqMultiAsset(PirTerm a, PirTerm b) {
        // For each policy in b:
        //   For each token under that policy in b:
        //     Check assetOf(a, policy, token) >= amount
        // All checks must pass (AND)

        var aVar = new PirTerm.Var("a_geq", new PirType.DataType());
        var bVar = new PirTerm.Var("b_geq", new PirType.DataType());

        // Outer fold over b's policies: foldl (\acc outerPair -> acc AND checkPolicy) True (UnMapData(b))
        var outerAccVar = new PirTerm.Var("oAcc_geq", new PirType.BoolType());
        var outerPairVar = new PirTerm.Var("oPair_geq", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));

        // Extract policy (FstPair) and inner token map (UnMapData(SndPair))
        var policyBs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), outerPairVar));
        var innerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), outerPairVar));

        // Inner fold over tokens: foldl (\iAcc iPair -> iAcc AND (assetOf(a, policy, token) >= amount)) True innerPairs
        var innerAccVar = new PirTerm.Var("iAcc_geq", new PirType.BoolType());
        var innerPairVar = new PirTerm.Var("iPair_geq", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));

        var tokenBs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), innerPairVar));
        var bAmount = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), innerPairVar));

        // assetOf(a, policy, token) >= bAmount
        var aAmount = assetOf(aVar, policyBs, tokenBs);
        var geqCheck = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.LessThanEqualsInteger), bAmount),
                aAmount);

        // iAcc AND geqCheck
        var innerBody = new PirTerm.IfThenElse(innerAccVar, geqCheck,
                new PirTerm.Const(Constant.bool(false)));
        var innerFoldFn = new PirTerm.Lam("iAcc_geq", new PirType.BoolType(),
                new PirTerm.Lam("iPair_geq", new PirType.PairType(new PirType.DataType(), new PirType.DataType()),
                        innerBody));

        var innerFold = ListsLib.foldl(innerFoldFn, new PirTerm.Const(Constant.bool(true)), innerPairs);

        // oAcc AND innerFold
        var outerBody = new PirTerm.IfThenElse(outerAccVar, innerFold,
                new PirTerm.Const(Constant.bool(false)));
        var outerFoldFn = new PirTerm.Lam("oAcc_geq", new PirType.BoolType(),
                new PirTerm.Lam("oPair_geq", new PirType.PairType(new PirType.DataType(), new PirType.DataType()),
                        outerBody));

        var bPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), bVar);
        var outerFold = ListsLib.foldl(outerFoldFn, new PirTerm.Const(Constant.bool(true)), bPairs);

        return new PirTerm.Let("a_geq", a,
                new PirTerm.Let("b_geq", b, outerFold));
    }

    /**
     * Checks if value a <= value b (multi-asset). Equivalent to geqMultiAsset(b, a).
     */
    public static PirTerm leq(PirTerm a, PirTerm b) {
        return geqMultiAsset(b, a);
    }

    /**
     * Checks if two values are equal (multi-asset). Equivalent to geq(a,b) AND geq(b,a).
     */
    public static PirTerm eq(PirTerm a, PirTerm b) {
        var aVar = new PirTerm.Var("a_veq", new PirType.DataType());
        var bVar = new PirTerm.Var("b_veq", new PirType.DataType());
        var geqAB = geqMultiAsset(aVar, bVar);
        var geqBA = geqMultiAsset(bVar, aVar);
        var result = new PirTerm.IfThenElse(geqAB, geqBA, new PirTerm.Const(Constant.bool(false)));
        return new PirTerm.Let("a_veq", a, new PirTerm.Let("b_veq", b, result));
    }

    /**
     * Checks if a value is zero (all amounts == 0).
     * <p>
     * Walks all policies and tokens, checks every amount == 0.
     */
    public static PirTerm isZero(PirTerm value) {
        // foldl over outer map, for each policy foldl over inner tokens, check all amounts == 0
        var outerAccVar = new PirTerm.Var("oAcc_iz", new PirType.BoolType());
        var outerPairVar = new PirTerm.Var("oPair_iz", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var innerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), outerPairVar));

        var innerAccVar = new PirTerm.Var("iAcc_iz", new PirType.BoolType());
        var innerPairVar = new PirTerm.Var("iPair_iz", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var amount = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), innerPairVar));
        var isAmtZero = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), amount),
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
        var innerBody = new PirTerm.IfThenElse(innerAccVar, isAmtZero, new PirTerm.Const(Constant.bool(false)));
        var innerFoldFn = new PirTerm.Lam("iAcc_iz", new PirType.BoolType(),
                new PirTerm.Lam("iPair_iz", new PirType.PairType(new PirType.DataType(), new PirType.DataType()), innerBody));
        var innerFold = ListsLib.foldl(innerFoldFn, new PirTerm.Const(Constant.bool(true)), innerPairs);

        var outerBody = new PirTerm.IfThenElse(outerAccVar, innerFold, new PirTerm.Const(Constant.bool(false)));
        var outerFoldFn = new PirTerm.Lam("oAcc_iz", new PirType.BoolType(),
                new PirTerm.Lam("oPair_iz", new PirType.PairType(new PirType.DataType(), new PirType.DataType()), outerBody));
        var outerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), value);
        return ListsLib.foldl(outerFoldFn, new PirTerm.Const(Constant.bool(true)), outerPairs);
    }

    /**
     * Constructs a Value containing a single asset.
     * <p>
     * Creates: Map[ (policy, Map[ (token, IData(amount)) ]) ]
     *
     * @param policyId  PIR term representing the policy (ByteString)
     * @param tokenName PIR term representing the token name (ByteString)
     * @param amount    PIR term representing the amount (Integer)
     * @return PIR term evaluating to a Value (MapData)
     */
    public static PirTerm singleton(PirTerm policyId, PirTerm tokenName, PirTerm amount) {
        var emptyPairList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                new PirTerm.Const(Constant.unit()));

        // Inner map: { tokenName -> IData(amount) }
        var innerPair = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData), tokenName)),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData), amount));
        var innerList = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), innerPair), emptyPairList);
        var innerMap = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), innerList);

        // Outer map: { policyId -> innerMap }
        var outerPair = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData),
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData), policyId)),
                innerMap);
        var outerList = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), outerPair), emptyPairList);
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), outerList);
    }

    /**
     * Negates all amounts in a value.
     * <p>
     * Walks all policy/token entries and negates each amount.
     */
    public static PirTerm negate(PirTerm value) {
        // For each policy, for each token: negate the amount
        // Build new map with negated amounts using nested folds
        var outerAccVar = new PirTerm.Var("oAcc_neg", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var outerPairVar = new PirTerm.Var("oPair_neg", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var policyKey = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), outerPairVar);
        var innerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), outerPairVar));

        // Inner fold: negate each token amount
        var innerAccVar = new PirTerm.Var("iAcc_neg", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var innerPairVar = new PirTerm.Var("iPair_neg", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var tokenKey = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), innerPairVar);
        var amt = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), innerPairVar));
        var negAmt = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                        new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                amt);
        var negPair = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData), tokenKey),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData), negAmt));
        var innerCons = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), negPair), innerAccVar);
        var innerFoldFn = new PirTerm.Lam("iAcc_neg", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirTerm.Lam("iPair_neg", new PirType.PairType(new PirType.DataType(), new PirType.DataType()), innerCons));
        var emptyPairList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                new PirTerm.Const(Constant.unit()));
        var innerFold = ListsLib.foldl(innerFoldFn, emptyPairList, innerPairs);
        var negInnerMap = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), innerFold);

        // Build new outer entry
        var newOuterPair = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData), policyKey), negInnerMap);
        var outerCons = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), newOuterPair), outerAccVar);
        var outerFoldFn = new PirTerm.Lam("oAcc_neg", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirTerm.Lam("oPair_neg", new PirType.PairType(new PirType.DataType(), new PirType.DataType()), outerCons));

        var outerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), value);
        var result = ListsLib.foldl(outerFoldFn, emptyPairList, outerPairs);
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), result);
    }

    /**
     * Flattens a Value into a list of (policy, token, amount) triples.
     * <p>
     * Each triple is encoded as ConstrData(0, [BData(policy), BData(token), IData(amount)]).
     */
    public static PirTerm flatten(PirTerm value) {
        // Nested fold: outer over policies, inner over tokens
        // Each iteration produces ConstrData(0, [policy, token, IData(amount)])
        var outerAccVar = new PirTerm.Var("oAcc_flat", new PirType.ListType(new PirType.DataType()));
        var outerPairVar = new PirTerm.Var("oPair_flat", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var policyData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), outerPairVar);
        var innerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), outerPairVar));

        var innerAccVar = new PirTerm.Var("iAcc_flat", new PirType.ListType(new PirType.DataType()));
        var innerPairVar = new PirTerm.Var("iPair_flat", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var tokenData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), innerPairVar);
        var amountData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), innerPairVar);

        // Build triple: ConstrData(0, [policy, token, amount])
        var nilData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var tripleFields = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), amountData), nilData);
        tripleFields = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), tokenData), tripleFields);
        tripleFields = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), policyData), tripleFields);
        var triple = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                        new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                tripleFields);

        var innerCons = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), triple), innerAccVar);
        var innerFoldFn = new PirTerm.Lam("iAcc_flat", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("iPair_flat", new PirType.PairType(new PirType.DataType(), new PirType.DataType()), innerCons));
        var innerFold = ListsLib.foldl(innerFoldFn, outerAccVar, innerPairs);

        var outerFoldFn = new PirTerm.Lam("oAcc_flat", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("oPair_flat", new PirType.PairType(new PirType.DataType(), new PirType.DataType()), innerFold));

        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var outerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), value);
        return ListsLib.reverse(ListsLib.foldl(outerFoldFn, emptyList, outerPairs));
    }

    /**
     * Adds two Values together (union of nested maps, adding amounts for matching policy/token).
     * <p>
     * For each (policy, tokenMap) in a, for each (token, amount) in tokenMap:
     *   new amount = amount + assetOf(b, policy, token)
     * Then add any entries from b that weren't in a.
     * <p>
     * Simplified approach: flatten both, merge, rebuild.
     * Actually, we use nested folds: for each policy in a's outer map,
     * for each token in that policy's inner map, add assetOf(b, policy, token).
     * Then for each policy/token in b, if not in a, add it.
     * <p>
     * For simplicity, we'll construct the result by walking a and adding b's amounts,
     * then walking b and adding entries whose policy/token is not in a (amount 0 from assetOf).
     * Since assetOf returns 0 for missing entries, we can use:
     *   result_amount = assetOf(a, p, t) + assetOf(b, p, t)
     * applied to the union of all policy/token pairs from both a and b.
     * <p>
     * Implementation: walk a's structure, adjust amounts by adding from b.
     * Then walk b and add any policy/token not already in the result.
     * This is complex. We simplify: negate b's missing entries are 0, so:
     *   For each entry in a: new_amt = amt_a + assetOf(b, policy, token)
     *   For each entry in b: if assetOf(a, policy, token) == 0, include it
     * This avoids double-counting.
     */
    public static PirTerm add(PirTerm a, PirTerm b) {
        // Strategy: Build result map by:
        // 1. Walk a: for each (policy, tokens), for each (token, amt): new_amt = amt + assetOf(b, policy, token)
        // 2. Walk b: for each (policy, tokens), for each (token, amt): if assetOf(a, policy, token) == 0, include
        // 3. Wrap both in MapData

        var aVar = new PirTerm.Var("a_add", new PirType.DataType());
        var bVar = new PirTerm.Var("b_add", new PirType.DataType());

        // Part 1: adjusted entries from a
        var adjustedA = buildAdjustedMap(aVar, bVar, true);

        // Part 2: extra entries from b (not in a)
        var extraB = buildExtraEntries(bVar, aVar);

        // Combine: merge the two pair lists, then wrap in MapData
        // adjustedA is a list of (policy, innerMap) pairs
        // extraB is a list of (policy, innerMap) pairs
        // We need to concat these pair lists and wrap in MapData
        // Use foldl to prepend extraB onto adjustedA
        var combinedPairs = concatPairLists(adjustedA, extraB);

        return new PirTerm.Let("a_add", a,
                new PirTerm.Let("b_add", b,
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), combinedPairs)));
    }

    /**
     * Subtracts value b from value a: add(a, negate(b)).
     */
    public static PirTerm subtract(PirTerm a, PirTerm b) {
        return add(a, negate(b));
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

    // =========================================================================
    // Private helpers for Value addition
    // =========================================================================

    /**
     * Walk value a's outer map. For each (policy, innerTokenMap):
     *   Walk innerTokenMap. For each (token, amt):
     *     new_amt = amt + assetOf(other, policy, token)
     *   Build new inner map with adjusted amounts.
     * Returns a list of outer pairs (pair list, NOT wrapped in MapData yet).
     */
    private static PirTerm buildAdjustedMap(PirTerm a, PirTerm other, boolean addOther) {
        // Outer fold: over UnMapData(a), accumulate a pair list
        var outerAccVar = new PirTerm.Var("oAcc_adj", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var outerPairVar = new PirTerm.Var("oPair_adj", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var policyData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), outerPairVar);
        var policyBs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), policyData);
        var innerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), outerPairVar));

        // Inner fold: over inner token pairs, build adjusted inner pair list
        var innerAccVar = new PirTerm.Var("iAcc_adj", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var innerPairVar = new PirTerm.Var("iPair_adj", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var tokenData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), innerPairVar);
        var tokenBs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), tokenData);
        var amtA = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), innerPairVar));

        // assetOf(other, policy, token) returns Integer
        var amtOther = assetOf(other, policyBs, tokenBs);
        // new_amt = amtA + amtOther
        var newAmt = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), amtA), amtOther);
        // Build new inner pair: MkPairData(tokenData, IData(newAmt))
        var newInnerPair = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData), tokenData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData), newAmt));
        var innerCons = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), newInnerPair), innerAccVar);
        var innerFoldFn = new PirTerm.Lam("iAcc_adj", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirTerm.Lam("iPair_adj", new PirType.PairType(new PirType.DataType(), new PirType.DataType()),
                        innerCons));
        var emptyPairList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                new PirTerm.Const(Constant.unit()));
        var innerFold = ListsLib.foldl(innerFoldFn, emptyPairList, innerPairs);
        var newInnerMap = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), innerFold);

        // Build new outer pair: MkPairData(policyData, newInnerMap)
        var newOuterPair = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData), policyData), newInnerMap);
        var outerCons = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), newOuterPair), outerAccVar);
        var outerFoldFn = new PirTerm.Lam("oAcc_adj", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirTerm.Lam("oPair_adj", new PirType.PairType(new PirType.DataType(), new PirType.DataType()),
                        outerCons));

        var outerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), a);
        return ListsLib.foldl(outerFoldFn, emptyPairList, outerPairs);
    }

    /**
     * Walk value b's outer map. For each (policy, innerTokenMap):
     *   Walk innerTokenMap. For each (token, amt):
     *     if assetOf(a, policy, token) == 0, include this entry (it's new in b)
     *   Build inner map with only entries not in a.
     * Skip entire policy if no tokens are new.
     * Returns a list of outer pairs.
     */
    private static PirTerm buildExtraEntries(PirTerm b, PirTerm a) {
        // Outer fold: over UnMapData(b)
        var outerAccVar = new PirTerm.Var("oAcc_ext", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var outerPairVar = new PirTerm.Var("oPair_ext", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var policyData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), outerPairVar);
        var policyBs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), policyData);
        var innerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), outerPairVar));

        // Inner fold: keep only entries where assetOf(a, policy, token) == 0
        var innerAccVar = new PirTerm.Var("iAcc_ext", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var innerPairVar = new PirTerm.Var("iPair_ext", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var tokenData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), innerPairVar);
        var tokenBs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), tokenData);

        var amtInA = assetOf(a, policyBs, tokenBs);
        var isZeroInA = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), amtInA),
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)));

        // If a has 0 for this asset, keep b's entry; otherwise skip (already counted in adjusted)
        var innerConsOrSkip = new PirTerm.IfThenElse(isZeroInA,
                new PirTerm.App(
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), innerPairVar), innerAccVar),
                innerAccVar);
        var innerFoldFn = new PirTerm.Lam("iAcc_ext", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirTerm.Lam("iPair_ext", new PirType.PairType(new PirType.DataType(), new PirType.DataType()),
                        innerConsOrSkip));
        var emptyPairList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                new PirTerm.Const(Constant.unit()));
        var innerFold = ListsLib.foldl(innerFoldFn, emptyPairList, innerPairs);

        // Only include outer entry if inner fold is non-empty
        var innerEmpty = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), innerFold);
        var newInnerMap = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), innerFold);
        var newOuterPair = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData), policyData), newInnerMap);

        // Let-bind innerFold to avoid duplicate evaluation
        var innerFoldVar = new PirTerm.Var("iFold_ext", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var conditionalCons = new PirTerm.IfThenElse(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), innerFoldVar),
                outerAccVar,
                new PirTerm.App(
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                                new PirTerm.App(
                                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData), policyData),
                                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), innerFoldVar))),
                        outerAccVar));
        var outerBody = new PirTerm.Let("iFold_ext", innerFold, conditionalCons);

        var outerFoldFn = new PirTerm.Lam("oAcc_ext", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirTerm.Lam("oPair_ext", new PirType.PairType(new PirType.DataType(), new PirType.DataType()),
                        outerBody));

        var outerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), b);
        return ListsLib.foldl(outerFoldFn, emptyPairList, outerPairs);
    }

    /**
     * Concatenate two pair lists using foldl.
     */
    private static PirTerm concatPairLists(PirTerm listA, PirTerm listB) {
        // foldl (\acc x -> MkCons(x, acc)) listA listB
        // This prepends all of listB onto listA
        var accVar = new PirTerm.Var("acc_cpl", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var xVar = new PirTerm.Var("x_cpl", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), xVar), accVar);
        var foldFn = new PirTerm.Lam("acc_cpl", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirTerm.Lam("x_cpl", new PirType.PairType(new PirType.DataType(), new PirType.DataType()),
                        consExpr));
        return ListsLib.foldl(foldFn, listA, listB);
    }
}
