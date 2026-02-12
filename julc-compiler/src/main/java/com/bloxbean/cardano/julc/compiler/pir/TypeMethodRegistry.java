package com.bloxbean.cardano.julc.compiler.pir;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;

import java.math.BigInteger;
import java.util.*;

/**
 * Registry mapping (PirType, methodName) to instance method handlers.
 * Decouples instance method dispatch from the PirGenerator compiler core.
 */
public final class TypeMethodRegistry {

    @FunctionalInterface
    public interface InstanceMethodHandler {
        PirTerm handle(PirTerm scope, List<PirTerm> args, PirType scopeType, List<PirType> argTypes);
    }

    @FunctionalInterface
    public interface ReturnTypeResolver {
        PirType resolve(PirType scopeType);
    }

    record MethodRegistration(InstanceMethodHandler handler, ReturnTypeResolver returnType) {}

    // Key: "IntegerType.abs", "ListType.contains", etc.
    private final Map<String, MethodRegistration> registry = new HashMap<>();

    public void register(String typeKey, String method,
                         InstanceMethodHandler handler, ReturnTypeResolver returnType) {
        registry.put(typeKey + "." + method, new MethodRegistration(handler, returnType));
    }

    public Optional<PirTerm> dispatch(PirTerm scope, String method,
                                       List<PirTerm> args, PirType scopeType, List<PirType> argTypes) {
        String key = scopeType.getClass().getSimpleName() + "." + method;
        var reg = registry.get(key);
        if (reg == null) return Optional.empty();
        return Optional.of(reg.handler().handle(scope, args, scopeType, argTypes));
    }

    public Optional<PirType> resolveReturnType(PirType scopeType, String method) {
        String key = scopeType.getClass().getSimpleName() + "." + method;
        var reg = registry.get(key);
        if (reg == null) return Optional.empty();
        return Optional.of(reg.returnType().resolve(scopeType));
    }

    public boolean contains(String typeKey, String method) {
        return registry.containsKey(typeKey + "." + method);
    }

    /**
     * Create a registry with all standard instance methods registered.
     */
    public static TypeMethodRegistry defaultRegistry() {
        var reg = new TypeMethodRegistry();
        registerIntegerMethods(reg);
        registerByteStringMethods(reg);
        registerStringMethods(reg);
        registerDataEqualsMethods(reg);
        registerListMethods(reg);
        registerOptionalMethods(reg);
        registerMapMethods(reg);
        return reg;
    }

    // --- Integer methods (5) ---

    private static void registerIntegerMethods(TypeMethodRegistry reg) {
        var zero = new PirTerm.Const(Constant.integer(BigInteger.ZERO));

        // abs: if x < 0 then 0 - x else x
        reg.register("IntegerType", "abs",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.IfThenElse(
                                PirHelpers.builtinApp2(DefaultFun.LessThanInteger, scope, zero),
                                PirHelpers.builtinApp2(DefaultFun.SubtractInteger, zero, scope),
                                scope),
                scopeType -> new PirType.IntegerType());

        // negate: 0 - x
        reg.register("IntegerType", "negate",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.builtinApp2(DefaultFun.SubtractInteger, zero, scope),
                scopeType -> new PirType.IntegerType());

        // max: if a < b then b else a
        reg.register("IntegerType", "max",
                (scope, args, scopeType, argTypes) -> {
                    var argPir = args.get(0);
                    return new PirTerm.IfThenElse(
                            PirHelpers.builtinApp2(DefaultFun.LessThanInteger, scope, argPir),
                            argPir, scope);
                },
                scopeType -> new PirType.IntegerType());

        // min: if a <= b then a else b
        reg.register("IntegerType", "min",
                (scope, args, scopeType, argTypes) -> {
                    var argPir = args.get(0);
                    return new PirTerm.IfThenElse(
                            PirHelpers.builtinApp2(DefaultFun.LessThanEqualsInteger, scope, argPir),
                            scope, argPir);
                },
                scopeType -> new PirType.IntegerType());

        // equals: EqualsInteger (with Data→Integer coercion on arg)
        reg.register("IntegerType", "equals",
                (scope, args, scopeType, argTypes) -> {
                    var argPir = args.get(0);
                    var argType = argTypes.get(0);
                    if (!(argType instanceof PirType.IntegerType)) {
                        argPir = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), argPir);
                    }
                    return PirHelpers.builtinApp2(DefaultFun.EqualsInteger, scope, argPir);
                },
                scopeType -> new PirType.BoolType());

        // Arithmetic instance methods (direct builtin wrappers)
        reg.register("IntegerType", "add",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.builtinApp2(DefaultFun.AddInteger, scope, args.get(0)),
                scopeType -> new PirType.IntegerType());

        reg.register("IntegerType", "subtract",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.builtinApp2(DefaultFun.SubtractInteger, scope, args.get(0)),
                scopeType -> new PirType.IntegerType());

        reg.register("IntegerType", "multiply",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.builtinApp2(DefaultFun.MultiplyInteger, scope, args.get(0)),
                scopeType -> new PirType.IntegerType());

        reg.register("IntegerType", "divide",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.builtinApp2(DefaultFun.DivideInteger, scope, args.get(0)),
                scopeType -> new PirType.IntegerType());

        reg.register("IntegerType", "remainder",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.builtinApp2(DefaultFun.RemainderInteger, scope, args.get(0)),
                scopeType -> new PirType.IntegerType());

        reg.register("IntegerType", "mod",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.builtinApp2(DefaultFun.ModInteger, scope, args.get(0)),
                scopeType -> new PirType.IntegerType());

        // signum: if x < 0 then -1 else if x == 0 then 0 else 1
        reg.register("IntegerType", "signum",
                (scope, args, scopeType, argTypes) -> {
                    var one = new PirTerm.Const(Constant.integer(BigInteger.ONE));
                    var negOne = new PirTerm.Const(Constant.integer(BigInteger.valueOf(-1)));
                    return new PirTerm.IfThenElse(
                            PirHelpers.builtinApp2(DefaultFun.LessThanInteger, scope, zero),
                            negOne,
                            new PirTerm.IfThenElse(
                                    PirHelpers.builtinApp2(DefaultFun.EqualsInteger, scope, zero),
                                    zero,
                                    one));
                },
                scopeType -> new PirType.IntegerType());

        // compareTo: if a < b then -1 else if a == b then 0 else 1
        reg.register("IntegerType", "compareTo",
                (scope, args, scopeType, argTypes) -> {
                    var argPir = args.get(0);
                    var one = new PirTerm.Const(Constant.integer(BigInteger.ONE));
                    var negOne = new PirTerm.Const(Constant.integer(BigInteger.valueOf(-1)));
                    return new PirTerm.IfThenElse(
                            PirHelpers.builtinApp2(DefaultFun.LessThanInteger, scope, argPir),
                            negOne,
                            new PirTerm.IfThenElse(
                                    PirHelpers.builtinApp2(DefaultFun.EqualsInteger, scope, argPir),
                                    zero,
                                    one));
                },
                scopeType -> new PirType.IntegerType());

        // intValue / longValue: identity (int, long, BigInteger are all IntegerType on-chain)
        reg.register("IntegerType", "intValue",
                (scope, args, scopeType, argTypes) -> scope,
                scopeType -> new PirType.IntegerType());
        reg.register("IntegerType", "longValue",
                (scope, args, scopeType, argTypes) -> scope,
                scopeType -> new PirType.IntegerType());
    }

    // --- ByteString methods (2) ---

    private static void registerByteStringMethods(TypeMethodRegistry reg) {
        // length: LengthOfByteString
        reg.register("ByteStringType", "length",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.LengthOfByteString), scope),
                scopeType -> new PirType.IntegerType());

        // equals: EqualsByteString (with Data→ByteString coercion on arg)
        reg.register("ByteStringType", "equals",
                (scope, args, scopeType, argTypes) -> {
                    var argPir = args.get(0);
                    var argType = argTypes.get(0);
                    if (!(argType instanceof PirType.ByteStringType)) {
                        argPir = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), argPir);
                    }
                    return PirHelpers.builtinApp2(DefaultFun.EqualsByteString, scope, argPir);
                },
                scopeType -> new PirType.BoolType());
    }

    // --- String methods (2) ---

    private static void registerStringMethods(TypeMethodRegistry reg) {
        // length: LengthOfByteString(EncodeUtf8(s))
        reg.register("StringType", "length",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.LengthOfByteString),
                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EncodeUtf8), scope)),
                scopeType -> new PirType.IntegerType());

        // equals: EqualsString
        reg.register("StringType", "equals",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.builtinApp2(DefaultFun.EqualsString, scope, args.get(0)),
                scopeType -> new PirType.BoolType());
    }

    // --- Data/Record/SumType .equals() (3 registrations, same handler) ---

    private static void registerDataEqualsMethods(TypeMethodRegistry reg) {
        InstanceMethodHandler equalsDataHandler =
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.builtinApp2(DefaultFun.EqualsData, scope, args.get(0));
        ReturnTypeResolver boolReturn = scopeType -> new PirType.BoolType();

        reg.register("DataType", "equals", equalsDataHandler, boolReturn);
        reg.register("RecordType", "equals", equalsDataHandler, boolReturn);
        reg.register("SumType", "equals", equalsDataHandler, boolReturn);
    }

    // --- List methods (5) ---

    private static void registerListMethods(TypeMethodRegistry reg) {
        // size: foldl-based length
        reg.register("ListType", "size",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.generateListLength(scope),
                scopeType -> new PirType.IntegerType());

        // isEmpty: NullList
        reg.register("ListType", "isEmpty",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), scope),
                scopeType -> new PirType.BoolType());

        // head: wrapDecode(HeadList, elemType)
        reg.register("ListType", "head",
                (scope, args, scopeType, argTypes) -> {
                    var lt = (PirType.ListType) scopeType;
                    return PirHelpers.wrapDecode(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), scope),
                            lt.elemType());
                },
                scopeType -> ((PirType.ListType) scopeType).elemType());

        // tail: TailList
        reg.register("ListType", "tail",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), scope),
                scopeType -> scopeType);

        // get(index): LetRec-based nth element access (O(n) linked list traversal)
        reg.register("ListType", "get",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("get() requires an index argument");
                    // LetRec pattern: go(lst, idx) = if idx == 0 then HeadList(lst) else go(TailList(lst), idx-1)
                    var lstVar = new PirTerm.Var("lst_get", new PirType.ListType(new PirType.DataType()));
                    var idxVar = new PirTerm.Var("idx_get", new PirType.IntegerType());
                    var goVar = new PirTerm.Var("go_get", new PirType.FunType(
                            new PirType.ListType(new PirType.DataType()),
                            new PirType.FunType(new PirType.IntegerType(), new PirType.DataType())));

                    var isZero = PirHelpers.builtinApp2(DefaultFun.EqualsInteger, idxVar,
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
                    var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
                    var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
                    var decIdx = PirHelpers.builtinApp2(DefaultFun.SubtractInteger, idxVar,
                            new PirTerm.Const(Constant.integer(BigInteger.ONE)));
                    var recurse = new PirTerm.App(new PirTerm.App(goVar, tailExpr), decIdx);

                    var body = new PirTerm.IfThenElse(isZero, headExpr, recurse);
                    var goBody = new PirTerm.Lam("lst_get", new PirType.ListType(new PirType.DataType()),
                            new PirTerm.Lam("idx_get", new PirType.IntegerType(), body));
                    var binding = new PirTerm.Binding("go_get", goBody);
                    var lt = (PirType.ListType) scopeType;
                    var raw = new PirTerm.LetRec(List.of(binding),
                            new PirTerm.App(new PirTerm.App(goVar, scope), args.get(0)));
                    return PirHelpers.wrapDecode(raw, lt.elemType());
                },
                scopeType -> ((PirType.ListType) scopeType).elemType());

        // contains: recursive search with typed equality
        reg.register("ListType", "contains",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("contains() requires one argument");
                    var lt = (PirType.ListType) scopeType;
                    var targetType = argTypes.get(0);
                    return PirHelpers.generateListContains(scope, args.get(0), lt.elemType(), targetType);
                },
                scopeType -> new PirType.BoolType());
    }

    // --- Optional methods (3) ---

    private static void registerOptionalMethods(TypeMethodRegistry reg) {
        // isPresent: FstPair(UnConstrData(x)) == 0
        reg.register("OptionalType", "isPresent",
                (scope, args, scopeType, argTypes) -> {
                    var tag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), scope));
                    return PirHelpers.builtinApp2(DefaultFun.EqualsInteger, tag,
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
                },
                scopeType -> new PirType.BoolType());

        // isEmpty: FstPair(UnConstrData(x)) == 1
        reg.register("OptionalType", "isEmpty",
                (scope, args, scopeType, argTypes) -> {
                    var tag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), scope));
                    return PirHelpers.builtinApp2(DefaultFun.EqualsInteger, tag,
                            new PirTerm.Const(Constant.integer(BigInteger.ONE)));
                },
                scopeType -> new PirType.BoolType());

        // get: wrapDecode(HeadList(SndPair(UnConstrData(x))), elemType)
        reg.register("OptionalType", "get",
                (scope, args, scopeType, argTypes) -> {
                    var opt = (PirType.OptionalType) scopeType;
                    var fields = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), scope));
                    var raw = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), fields);
                    return PirHelpers.wrapDecode(raw, opt.elemType());
                },
                scopeType -> ((PirType.OptionalType) scopeType).elemType());
    }

    // --- Map methods (3): get, containsKey, size ---

    private static void registerMapMethods(TypeMethodRegistry reg) {
        // get(key): LetRec lookup through association list pairs, returns Optional
        reg.register("MapType", "get",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("map.get() requires a key argument");
                    var mapVar = new PirTerm.Var("m_get", new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                    var keyVar = new PirTerm.Var("k_get", new PirType.DataType());

                    // UnMapData to get pair list
                    var pairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), mapVar);
                    var pairsVar = new PirTerm.Var("ps_get", new PirType.ListType(
                            new PirType.PairType(new PirType.DataType(), new PirType.DataType())));

                    // LetRec: go(pairs) = if NullList(pairs) then None else ...
                    var lstVar = new PirTerm.Var("lst_get", new PirType.ListType(
                            new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
                    var goVar = new PirTerm.Var("go_get", new PirType.FunType(
                            new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                            new PirType.DataType()));

                    var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
                    // None = ConstrData(1, [])
                    var mkNil = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                            new PirTerm.Const(Constant.unit()));
                    var none = new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                                    new PirTerm.Const(Constant.integer(BigInteger.ONE))),
                            mkNil);

                    var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
                    var hVar = new PirTerm.Var("h_get", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
                    var fstH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), hVar);
                    var sndH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), hVar);
                    var eqCheck = PirHelpers.builtinApp2(DefaultFun.EqualsData, fstH, keyVar);

                    // Some(value) = ConstrData(0, [value])
                    var someFields = new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), sndH), mkNil);
                    var some = new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            someFields);

                    var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
                    var recurse = new PirTerm.App(goVar, tailExpr);
                    var innerIf = new PirTerm.IfThenElse(eqCheck, some, recurse);
                    var letHead = new PirTerm.Let("h_get", headExpr, innerIf);
                    var outerIf = new PirTerm.IfThenElse(nullCheck, none, letHead);

                    var goBody = new PirTerm.Lam("lst_get", new PirType.ListType(
                            new PirType.PairType(new PirType.DataType(), new PirType.DataType())), outerIf);
                    var binding = new PirTerm.Binding("go_get", goBody);
                    var search = new PirTerm.LetRec(List.of(binding), new PirTerm.App(goVar, pairsVar));

                    return new PirTerm.Let("m_get", scope,
                            new PirTerm.Let("k_get", args.get(0),
                                    new PirTerm.Let("ps_get", pairs, search)));
                },
                scopeType -> new PirType.OptionalType(((PirType.MapType) scopeType).valueType()));

        // containsKey(key): LetRec search returning Bool
        reg.register("MapType", "containsKey",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("map.containsKey() requires a key argument");
                    var mapVar = new PirTerm.Var("m_ck", new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                    var keyVar = new PirTerm.Var("k_ck", new PirType.DataType());

                    var pairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), mapVar);
                    var pairsVar = new PirTerm.Var("ps_ck", new PirType.ListType(
                            new PirType.PairType(new PirType.DataType(), new PirType.DataType())));

                    var lstVar = new PirTerm.Var("lst_ck", new PirType.ListType(
                            new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
                    var goVar = new PirTerm.Var("go_ck", new PirType.FunType(
                            new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                            new PirType.BoolType()));

                    var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
                    var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
                    var hVar = new PirTerm.Var("h_ck", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
                    var fstH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), hVar);
                    var eqCheck = PirHelpers.builtinApp2(DefaultFun.EqualsData, fstH, keyVar);

                    var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
                    var recurse = new PirTerm.App(goVar, tailExpr);
                    var innerIf = new PirTerm.IfThenElse(eqCheck,
                            new PirTerm.Const(Constant.bool(true)), recurse);
                    var letHead = new PirTerm.Let("h_ck", headExpr, innerIf);
                    var outerIf = new PirTerm.IfThenElse(nullCheck,
                            new PirTerm.Const(Constant.bool(false)), letHead);

                    var goBody = new PirTerm.Lam("lst_ck", new PirType.ListType(
                            new PirType.PairType(new PirType.DataType(), new PirType.DataType())), outerIf);
                    var binding = new PirTerm.Binding("go_ck", goBody);
                    var search = new PirTerm.LetRec(List.of(binding), new PirTerm.App(goVar, pairsVar));

                    return new PirTerm.Let("m_ck", scope,
                            new PirTerm.Let("k_ck", args.get(0),
                                    new PirTerm.Let("ps_ck", pairs, search)));
                },
                scopeType -> new PirType.BoolType());

        // size(): foldl-based count of map pairs
        reg.register("MapType", "size",
                (scope, args, scopeType, argTypes) -> {
                    var pairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), scope);
                    return PirHelpers.generateListLength(pairs);
                },
                scopeType -> new PirType.IntegerType());
    }
}
