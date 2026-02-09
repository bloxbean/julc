package com.bloxbean.cardano.plutus.compiler.pir;

import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;

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
