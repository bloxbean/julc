package com.bloxbean.cardano.julc.compiler.pir;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;

import java.math.BigInteger;
import java.util.List;

/**
 * Static utility methods for PIR term construction.
 * Used by both PirGenerator and TypeMethodRegistry.
 */
public final class PirHelpers {

    private PirHelpers() {}

    static PirTerm builtinApp2(DefaultFun fun, PirTerm a, PirTerm b) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(fun), a),
                b);
    }

    /**
     * Wrap a raw Data term with the appropriate decode builtin for the target type.
     */
    public static PirTerm wrapDecode(PirTerm data, PirType targetType) {
        if (targetType instanceof PirType.IntegerType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), data);
        }
        if (targetType instanceof PirType.ByteStringType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), data);
        }
        if (targetType instanceof PirType.ListType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), data);
        }
        if (targetType instanceof PirType.MapType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), data);
        }
        if (targetType instanceof PirType.BoolType) {
            // Bool encoded as Constr: False=Constr(0,[]), True=Constr(1,[])
            // Decode: FstPair(UnConstrData(data)) == 1
            var tag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), data));
            return builtinApp2(DefaultFun.EqualsInteger, tag,
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        }
        if (targetType instanceof PirType.StringType) {
            // String encoded as BData(EncodeUtf8(s)) in Data. Decode: DecodeUtf8(UnBData(data))
            var byteString = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), data);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.DecodeUtf8), byteString);
        }
        // For Data, RecordType, SumType, etc., pass through as raw Data
        return data;
    }

    /**
     * Wrap a value with the appropriate encode builtin for the target type (inverse of wrapDecode).
     */
    public static PirTerm wrapEncode(PirTerm value, PirType type) {
        if (type instanceof PirType.IntegerType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData), value);
        }
        if (type instanceof PirType.ByteStringType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData), value);
        }
        if (type instanceof PirType.BoolType) {
            // Bool → ConstrData: False=Constr(0,[]), True=Constr(1,[])
            var nilData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));
            var trueData = builtinApp2(DefaultFun.ConstrData,
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)), nilData);
            var falseData = builtinApp2(DefaultFun.ConstrData,
                    new PirTerm.Const(Constant.integer(BigInteger.ZERO)), nilData);
            return new PirTerm.IfThenElse(value, trueData, falseData);
        }
        if (type instanceof PirType.StringType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EncodeUtf8), value));
        }
        if (type instanceof PirType.ListType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.ListData), value);
        }
        if (type instanceof PirType.MapType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), value);
        }
        // For Data, RecordType, SumType, etc., already Data — pass through
        return value;
    }

    /**
     * Generate PIR for list.size() — foldl(\acc _ -> acc + 1, 0, list).
     */
    static PirTerm generateListLength(PirTerm list) {
        var accVar = new PirTerm.Var("acc__len", new PirType.IntegerType());
        var xVar = new PirTerm.Var("_x__len", new PirType.DataType());
        var addOne = builtinApp2(DefaultFun.AddInteger, accVar,
                new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        var foldFn = new PirTerm.Lam("acc__len", new PirType.IntegerType(),
                new PirTerm.Lam("_x__len", new PirType.DataType(), addOne));
        return generateFoldl(foldFn, new PirTerm.Const(Constant.integer(BigInteger.ZERO)), list);
    }

    /**
     * Generate PIR for list.contains(target) — recursive search with typed equality.
     */
    static PirTerm generateListContains(PirTerm list, PirTerm target,
                                         PirType elemType, PirType targetType) {
        var lstVar = new PirTerm.Var("lst_c", new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var("go_c", new PirType.FunType(
                new PirType.ListType(new PirType.DataType()), new PirType.BoolType()));
        var targetVar = new PirTerm.Var("target_c", targetType);
        var listVar = new PirTerm.Var("list_c", new PirType.ListType(new PirType.DataType()));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var hVar = new PirTerm.Var("h_c", new PirType.DataType());

        // Build equality: decode list element, but only decode target if it's still Data
        PirTerm equalCheck = buildContainsEquality(hVar, targetVar, elemType, targetType);

        var recurse = new PirTerm.App(goVar, tailExpr);
        var innerIf = new PirTerm.IfThenElse(equalCheck,
                new PirTerm.Const(Constant.bool(true)), recurse);
        var letHead = new PirTerm.Let("h_c", headExpr, innerIf);
        var outerIf = new PirTerm.IfThenElse(nullCheck,
                new PirTerm.Const(Constant.bool(false)), letHead);

        var goBody = new PirTerm.Lam("lst_c", new PirType.ListType(new PirType.DataType()), outerIf);
        var binding = new PirTerm.Binding("go_c", goBody);

        var search = new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(goVar, listVar));

        return new PirTerm.Let("target_c", target,
                new PirTerm.Let("list_c", list, search));
    }

    /**
     * Build equality check for contains(). Always decodes the list element (it's raw Data).
     * Only decodes the target if it's still Data (not already decoded by field extraction).
     */
    static PirTerm buildContainsEquality(PirTerm listElem, PirTerm target,
                                          PirType elemType, PirType targetType) {
        if (elemType instanceof PirType.ByteStringType) {
            var decodedElem = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), listElem);
            var decodedTarget = (targetType instanceof PirType.ByteStringType) ?
                    target :
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), target);
            return builtinApp2(DefaultFun.EqualsByteString, decodedElem, decodedTarget);
        }
        if (elemType instanceof PirType.IntegerType) {
            var decodedElem = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), listElem);
            var decodedTarget = (targetType instanceof PirType.IntegerType) ?
                    target :
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), target);
            return builtinApp2(DefaultFun.EqualsInteger, decodedElem, decodedTarget);
        }
        // Default: EqualsData — both are Data
        return builtinApp2(DefaultFun.EqualsData, listElem, target);
    }

    /**
     * Callback for pair list search body construction.
     * Called with the head pair variable, key variable, and recursive call on tail.
     */
    @FunctionalInterface
    public interface PairSearchBody {
        PirTerm build(PirTerm.Var headPair, PirTerm.Var keyVar, PirTerm recurseOnTail);
    }

    /**
     * Generate a LetRec search over a pair list, matching by key.
     * <p>
     * Produces:
     * <pre>{@code
     * Let ps_<suffix> = list in
     * Let k_<suffix> = key in
     * LetRec go_<suffix>(lst) =
     *     if null(lst) then baseCase
     *     else let h_<suffix> = head(lst) in
     *       bodyBuilder(h_<suffix>, k_<suffix>, go(tail(lst)))
     * in go_<suffix>(ps_<suffix>)
     * }</pre>
     *
     * @param suffix      unique suffix for variable names (e.g., "get", "ck", "del")
     * @param list        the pair list to search (scope term)
     * @param key         the key to search for (pre-encoded as Data)
     * @param baseCase    result when list is exhausted
     * @param bodyBuilder produces the body for the non-null case
     * @return the complete LetRec search term
     */
    public static PirTerm pairListSearch(String suffix, PirTerm list, PirTerm key,
                                          PirTerm baseCase, PairSearchBody bodyBuilder) {
        var pairType = new PirType.PairType(new PirType.DataType(), new PirType.DataType());
        var pairListType = new PirType.ListType(pairType);

        var pairsVar = new PirTerm.Var("ps_" + suffix, pairListType);
        var keyVar = new PirTerm.Var("k_" + suffix, new PirType.DataType());
        var lstVar = new PirTerm.Var("lst_" + suffix, pairListType);
        var goVar = new PirTerm.Var("go_" + suffix,
                new PirType.FunType(pairListType, new PirType.DataType()));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var hVar = new PirTerm.Var("h_" + suffix, pairType);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var recurse = new PirTerm.App(goVar, tailExpr);

        var innerBody = bodyBuilder.build(hVar, keyVar, recurse);
        var letHead = new PirTerm.Let("h_" + suffix, headExpr, innerBody);
        var outerIf = new PirTerm.IfThenElse(nullCheck, baseCase, letHead);

        var goBody = new PirTerm.Lam("lst_" + suffix, pairListType, outerIf);
        var binding = new PirTerm.Binding("go_" + suffix, goBody);
        var search = new PirTerm.LetRec(List.of(binding), new PirTerm.App(goVar, pairsVar));

        return new PirTerm.Let("ps_" + suffix, list,
                new PirTerm.Let("k_" + suffix, key, search));
    }

    /**
     * Callback for regular list search body construction.
     * Called with the head element, target value, and recursive call on tail.
     */
    @FunctionalInterface
    public interface ListSearchBody {
        PirTerm build(PirTerm element, PirTerm target, PirTerm recurseOnTail);
    }

    /**
     * Generate a LetRec search over a regular Data list.
     * <p>
     * Produces:
     * <pre>{@code
     * Let xs_<suffix> = list in
     * Let t_<suffix> = target in
     * LetRec go_<suffix>(lst) =
     *     if null(lst) then baseCase
     *     else let e_<suffix> = head(lst) in
     *       bodyBuilder(e_<suffix>, t_<suffix>, go(tail(lst)))
     * in go_<suffix>(xs_<suffix>)
     * }</pre>
     *
     * @param suffix      unique suffix for variable names
     * @param list        the list to search
     * @param target      the target value (pre-encoded as Data)
     * @param baseCase    result when list is exhausted
     * @param bodyBuilder produces the body for the non-null case
     * @return the complete LetRec search term
     */
    public static PirTerm listSearch(String suffix, PirTerm list, PirTerm target,
                                      PirTerm baseCase, ListSearchBody bodyBuilder) {
        var dataListType = new PirType.ListType(new PirType.DataType());

        var xsVar = new PirTerm.Var("xs_" + suffix, dataListType);
        var tVar = new PirTerm.Var("t_" + suffix, new PirType.DataType());
        var lstVar = new PirTerm.Var("lst_" + suffix, dataListType);
        var goVar = new PirTerm.Var("go_" + suffix,
                new PirType.FunType(dataListType, new PirType.DataType()));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var eVar = new PirTerm.Var("e_" + suffix, new PirType.DataType());
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var recurse = new PirTerm.App(goVar, tailExpr);

        var innerBody = bodyBuilder.build(eVar, tVar, recurse);
        var letHead = new PirTerm.Let("e_" + suffix, headExpr, innerBody);
        var outerIf = new PirTerm.IfThenElse(nullCheck, baseCase, letHead);

        var goBody = new PirTerm.Lam("lst_" + suffix, dataListType, outerIf);
        var binding = new PirTerm.Binding("go_" + suffix, goBody);
        var search = new PirTerm.LetRec(List.of(binding), new PirTerm.App(goVar, xsVar));

        return new PirTerm.Let("xs_" + suffix, list,
                new PirTerm.Let("t_" + suffix, target, search));
    }

    /** Convenience: None value (ConstrData(1, [])). */
    public static PirTerm mkNone() {
        var mkNil = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        return builtinApp2(DefaultFun.ConstrData,
                new PirTerm.Const(Constant.integer(BigInteger.ONE)), mkNil);
    }

    /** Convenience: Some(value) = ConstrData(0, [value]). */
    public static PirTerm mkSome(PirTerm value) {
        var mkNil = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var fields = builtinApp2(DefaultFun.MkCons, value, mkNil);
        return builtinApp2(DefaultFun.ConstrData,
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)), fields);
    }

    /** Convenience: empty pair list (MkNilPairData(unit)). */
    public static PirTerm mkNilPairData() {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilPairData),
                new PirTerm.Const(Constant.unit()));
    }

    /**
     * Generate PIR foldl using LetRec — same pattern as ListsLib.foldl.
     */
    static PirTerm generateFoldl(PirTerm f, PirTerm init, PirTerm list) {
        var accVar = new PirTerm.Var("acc__f", new PirType.DataType());
        var lstVar = new PirTerm.Var("lst__f", new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var("go__f", new PirType.FunType(new PirType.DataType(),
                new PirType.FunType(new PirType.ListType(new PirType.DataType()), new PirType.DataType())));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);

        var fApp = new PirTerm.App(new PirTerm.App(f, accVar), headExpr);
        var recurse = new PirTerm.App(new PirTerm.App(goVar, fApp), tailExpr);

        var ifExpr = new PirTerm.IfThenElse(nullCheck, accVar, recurse);

        var goBody = new PirTerm.Lam("acc__f", new PirType.DataType(),
                new PirTerm.Lam("lst__f", new PirType.ListType(new PirType.DataType()), ifExpr));
        var binding = new PirTerm.Binding("go__f", goBody);

        return new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(new PirTerm.App(goVar, init), list));
    }
}
