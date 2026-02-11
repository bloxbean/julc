package com.bloxbean.cardano.plutus.stdlib.legacy;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;

import java.math.BigInteger;
import java.util.List;

/**
 * Map (association list) operations built as PIR term builders.
 * <p>
 * In Plutus, maps are represented as {@code List<Pair<Data, Data>>} (association lists).
 * These are NOT hash maps — lookups are O(n).
 */
public final class MapLib {

    private MapLib() {}

    /**
     * Looks up a key in a map, returning Optional (Some(value) or None).
     * <p>
     * Searches through the association list comparing keys with EqualsData.
     * Returns Constr(0, [value]) if found, Constr(1, []) if not found.
     *
     * @param map PIR term representing a map (List of Pairs as Data)
     * @param key PIR term representing the key (Data)
     * @return PIR term evaluating to Optional Data
     */
    public static PirTerm lookup(PirTerm map, PirTerm key) {
        // letrec go = \pairs ->
        //   IfThenElse(NullList(pairs),
        //     ConstrData(1, []),  -- None
        //     let h = HeadList(pairs) in
        //       IfThenElse(EqualsData(FstPair(h), key),
        //         ConstrData(0, [SndPair(h)]),  -- Some(value)
        //         go(TailList(pairs))))
        // in go(UnMapData(map))

        var pairsVar = new PirTerm.Var("pairs_lu", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var goVar = new PirTerm.Var("go_lu", new PirType.FunType(
                new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirType.DataType()));
        var keyVar = new PirTerm.Var("key_lu", new PirType.DataType());

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), pairsVar);
        var none = constrData(1, List.of());
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), pairsVar);
        var hVar = new PirTerm.Var("h_lu", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var fstH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), hVar);
        var sndH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), hVar);
        var eqCheck = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsData), fstH), keyVar);
        var some = constrData(0, List.of(sndH));
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), pairsVar);
        var recurse = new PirTerm.App(goVar, tailExpr);

        var innerIf = new PirTerm.IfThenElse(eqCheck, some, recurse);
        var letHead = new PirTerm.Let("h_lu", headExpr, innerIf);
        var outerIf = new PirTerm.IfThenElse(nullCheck, none, letHead);

        var goBody = new PirTerm.Lam("pairs_lu", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())), outerIf);
        var binding = new PirTerm.Binding("go_lu", goBody);

        var unmapExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), map);

        return new PirTerm.Let("key_lu", key,
                new PirTerm.LetRec(List.of(binding),
                        new PirTerm.App(goVar, unmapExpr)));
    }

    /**
     * Checks whether a key exists in a map.
     *
     * @param map PIR term representing a map
     * @param key PIR term representing the key
     * @return PIR term evaluating to Bool
     */
    public static PirTerm member(PirTerm map, PirTerm key) {
        // letrec go = \pairs ->
        //   IfThenElse(NullList(pairs),
        //     False,
        //     let h = HeadList(pairs) in
        //       IfThenElse(EqualsData(FstPair(h), key),
        //         True,
        //         go(TailList(pairs))))
        // in go(UnMapData(map))

        var pairsVar = new PirTerm.Var("pairs_mem", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var goVar = new PirTerm.Var("go_mem", new PirType.FunType(
                new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirType.BoolType()));
        var keyVar = new PirTerm.Var("key_mem", new PirType.DataType());

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), pairsVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), pairsVar);
        var hVar = new PirTerm.Var("h_mem", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var fstH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), hVar);
        var eqCheck = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsData), fstH), keyVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), pairsVar);
        var recurse = new PirTerm.App(goVar, tailExpr);

        var innerIf = new PirTerm.IfThenElse(eqCheck,
                new PirTerm.Const(Constant.bool(true)), recurse);
        var letHead = new PirTerm.Let("h_mem", headExpr, innerIf);
        var outerIf = new PirTerm.IfThenElse(nullCheck,
                new PirTerm.Const(Constant.bool(false)), letHead);

        var goBody = new PirTerm.Lam("pairs_mem", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())), outerIf);
        var binding = new PirTerm.Binding("go_mem", goBody);

        var unmapExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), map);

        return new PirTerm.Let("key_mem", key,
                new PirTerm.LetRec(List.of(binding),
                        new PirTerm.App(goVar, unmapExpr)));
    }

    /**
     * Inserts a key-value pair into a map (prepends to association list).
     * <p>
     * Note: does NOT remove any existing entry with the same key.
     * The new entry will shadow the old one on lookup.
     *
     * @param map   PIR term representing a map (Data: MapData)
     * @param key   PIR term representing the key (Data)
     * @param value PIR term representing the value (Data)
     * @return PIR term evaluating to a new map (Data)
     */
    public static PirTerm insert(PirTerm map, PirTerm key, PirTerm value) {
        // MapData(MkCons(MkPairData(key, value), UnMapData(map)))
        var pair = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData), key), value);
        var unmapExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), map);
        var newList = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), pair), unmapExpr);
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), newList);
    }

    /**
     * Deletes a key from a map (rebuilds list skipping matching key).
     *
     * @param map PIR term representing a map
     * @param key PIR term representing the key to delete
     * @return PIR term evaluating to a new map without the key
     */
    public static PirTerm delete(PirTerm map, PirTerm key) {
        // letrec go = \pairs -> \acc ->
        //   IfThenElse(NullList(pairs),
        //     acc,
        //     let h = HeadList(pairs) in
        //       IfThenElse(EqualsData(FstPair(h), key),
        //         go(TailList(pairs))(acc),         -- skip matching
        //         go(TailList(pairs))(MkCons(h, acc))))   -- keep
        // in MapData(reverse(go(UnMapData(map))(MkNilPairData)))

        var pairsVar = new PirTerm.Var("pairs_del", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var accVar = new PirTerm.Var("acc_del", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var goVar = new PirTerm.Var("go_del", new PirType.FunType(
                new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirType.FunType(
                        new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                        new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())))));
        var keyVar = new PirTerm.Var("key_del", new PirType.DataType());

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), pairsVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), pairsVar);
        var hVar = new PirTerm.Var("h_del", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var fstH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), hVar);
        var eqCheck = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsData), fstH), keyVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), pairsVar);
        var skipRecurse = new PirTerm.App(new PirTerm.App(goVar, tailExpr), accVar);
        var keepExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), hVar), accVar);
        var keepRecurse = new PirTerm.App(new PirTerm.App(goVar, tailExpr), keepExpr);

        var innerIf = new PirTerm.IfThenElse(eqCheck, skipRecurse, keepRecurse);
        var letHead = new PirTerm.Let("h_del", headExpr, innerIf);
        var outerIf = new PirTerm.IfThenElse(nullCheck, accVar, letHead);

        var goBody = new PirTerm.Lam("pairs_del", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirTerm.Lam("acc_del", new PirType.ListType(
                        new PirType.PairType(new PirType.DataType(), new PirType.DataType())), outerIf));
        var binding = new PirTerm.Binding("go_del", goBody);

        var unmapExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), map);
        var emptyPairList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                new PirTerm.Const(Constant.unit()));

        // We need to reverse the accumulated list to maintain order, then wrap in MapData
        // But the accumulated pairs are already Pair<Data,Data>, so we use a list-level reverse
        var collected = new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(new PirTerm.App(goVar, unmapExpr), emptyPairList));

        // Reverse the pair list using foldl with MkCons
        var reversedPairs = reversePairList(collected);

        return new PirTerm.Let("key_del", key,
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), reversedPairs));
    }

    /**
     * Extracts all keys from a map as a list of Data.
     *
     * @param map PIR term representing a map
     * @return PIR term evaluating to a builtin list of Data (the keys)
     */
    public static PirTerm keys(PirTerm map) {
        // foldl (\acc pair -> MkCons(FstPair(pair), acc)) MkNilData (UnMapData(map))
        // then reverse
        var accVar = new PirTerm.Var("acc_keys", new PirType.ListType(new PirType.DataType()));
        var pairVar = new PirTerm.Var("p_keys", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var fstExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), pairVar);
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), fstExpr), accVar);
        var foldFn = new PirTerm.Lam("acc_keys", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("p_keys", new PirType.PairType(new PirType.DataType(), new PirType.DataType()),
                        consExpr));
        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var unmapExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), map);
        return ListsLib.reverse(ListsLib.foldl(foldFn, emptyList, unmapExpr));
    }

    /**
     * Extracts all values from a map as a list of Data.
     *
     * @param map PIR term representing a map
     * @return PIR term evaluating to a builtin list of Data (the values)
     */
    public static PirTerm values(PirTerm map) {
        var accVar = new PirTerm.Var("acc_vals", new PirType.ListType(new PirType.DataType()));
        var pairVar = new PirTerm.Var("p_vals", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var sndExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), pairVar);
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), sndExpr), accVar);
        var foldFn = new PirTerm.Lam("acc_vals", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("p_vals", new PirType.PairType(new PirType.DataType(), new PirType.DataType()),
                        consExpr));
        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var unmapExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), map);
        return ListsLib.reverse(ListsLib.foldl(foldFn, emptyList, unmapExpr));
    }

    /**
     * Converts a map to its underlying pair list (identity — maps ARE pair lists).
     *
     * @param map PIR term representing a map
     * @return PIR term evaluating to a list of pairs
     */
    public static PirTerm toList(PirTerm map) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), map);
    }

    /**
     * Constructs a map from a pair list (identity — pair lists ARE maps).
     *
     * @param list PIR term representing a list of pairs
     * @return PIR term evaluating to a map (MapData)
     */
    public static PirTerm fromList(PirTerm list) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), list);
    }

    /**
     * Returns the number of entries in a map.
     *
     * @param map PIR term representing a map
     * @return PIR term evaluating to Integer
     */
    public static PirTerm size(PirTerm map) {
        var unmapExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), map);
        return ListsLib.length(unmapExpr);
    }

    // ---- Internal helpers ----

    /**
     * Build a Constr Data value at the PIR level.
     */
    private static PirTerm constrData(int tag, List<PirTerm> fields) {
        PirTerm list = new PirTerm.App(
                new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        for (int i = fields.size() - 1; i >= 0; i--) {
            list = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), fields.get(i)),
                    list);
        }
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                        new PirTerm.Const(Constant.integer(BigInteger.valueOf(tag)))),
                list);
    }

    /**
     * Reverse a pair list using foldl.
     */
    private static PirTerm reversePairList(PirTerm pairList) {
        var accVar = new PirTerm.Var("acc_rpair", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var xVar = new PirTerm.Var("x_rpair", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), xVar), accVar);
        var foldFn = new PirTerm.Lam("acc_rpair", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirTerm.Lam("x_rpair", new PirType.PairType(new PirType.DataType(), new PirType.DataType()),
                        consExpr));
        var emptyPairList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                new PirTerm.Const(Constant.unit()));
        return ListsLib.foldl(foldFn, emptyPairList, pairList);
    }
}
