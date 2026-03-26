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
        // Try named key first for RecordType (e.g., "Value.lovelaceOf" before "RecordType.lovelaceOf")
        if (scopeType instanceof PirType.RecordType rt) {
            var namedReg = registry.get(rt.name() + "." + method);
            if (namedReg != null) return Optional.of(namedReg.handler().handle(scope, args, scopeType, argTypes));
        }
        String key = scopeType.getClass().getSimpleName() + "." + method;
        var reg = registry.get(key);
        if (reg == null) return Optional.empty();
        return Optional.of(reg.handler().handle(scope, args, scopeType, argTypes));
    }

    public Optional<PirType> resolveReturnType(PirType scopeType, String method) {
        // Try named key first for RecordType (e.g., "Value.lovelaceOf" before "RecordType.lovelaceOf")
        if (scopeType instanceof PirType.RecordType rt) {
            var namedReg = registry.get(rt.name() + "." + method);
            if (namedReg != null) return Optional.of(namedReg.returnType().resolve(scopeType));
        }
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
        registerListHofMethods(reg);
        registerOptionalMethods(reg);
        registerMapMethods(reg);
        registerPairMethods(reg);
        registerValueMethods(reg);
        registerArrayMethods(reg);
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

        // value(): identity — for PlutusData.IntData.value() compatibility.
        // On-chain, unIData() already produces Integer; .value() is a no-op.
        reg.register("IntegerType", "value",
                (scope, args, scopeType, argTypes) -> scope,
                scopeType -> new PirType.IntegerType());
    }

    // --- ByteString methods ---

    private static void registerByteStringMethods(TypeMethodRegistry reg) {
        // hash: UnBData — for hash wrapper types (PubKeyHash.hash(), TxId.hash(), etc.)
        // On-chain, ByteStringType vars from list iteration hold Data-wrapped values.
        // UnBData extracts the ByteString. For field-access paths (e.g. pk.hash() in switch),
        // the compiler uses resolveRecordFieldAccess with wrapDecode, not TypeMethodRegistry.
        reg.register("ByteStringType", "hash",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), scope),
                scopeType -> new PirType.ByteStringType());

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

        // append: AppendByteString(bs1, bs2) — concatenate two bytestrings
        reg.register("ByteStringType", "append",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.builtinApp2(DefaultFun.AppendByteString, scope, args.get(0)),
                scopeType -> new PirType.ByteStringType());

        // prepend: ConsByteString(byte, bs) — prepend a single byte (integer 0-255) to bytestring
        reg.register("ByteStringType", "prepend",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.builtinApp2(DefaultFun.ConsByteString, args.get(0), scope),
                scopeType -> new PirType.ByteStringType());

        // value(): identity — for PlutusData.BytesData.value() compatibility.
        // On-chain, unBData() already produces ByteString; .value() is a no-op.
        reg.register("ByteStringType", "value",
                (scope, args, scopeType, argTypes) -> scope,
                scopeType -> new PirType.ByteStringType());
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

        // getBytes: EncodeUtf8(s) — convert String to byte[]
        reg.register("StringType", "getBytes",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.EncodeUtf8), scope),
                scopeType -> new PirType.ByteStringType());
    }

    // --- Data/Record/SumType .equals() (3 registrations, same handler) ---

    private static void registerDataEqualsMethods(TypeMethodRegistry reg) {
        InstanceMethodHandler equalsDataHandler =
                (scope, args, scopeType, argTypes) -> {
                    var encodedArg = PirHelpers.wrapEncode(args.get(0), argTypes.isEmpty() ? new PirType.DataType() : argTypes.get(0));
                    return PirHelpers.builtinApp2(DefaultFun.EqualsData, scope, encodedArg);
                };
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
                    if (args.isEmpty()) throw new CompilerException("get() requires an index argument. Usage: list.get(0)");
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

        // prepend: MkCons(wrapEncode(element), list) — prepend element to front of list
        reg.register("ListType", "prepend",
                (scope, args, scopeType, argTypes) -> {
                    var elemType = argTypes.isEmpty() ? new PirType.DataType() : argTypes.get(0);
                    var wrappedArg = PirHelpers.wrapEncode(args.get(0), elemType);
                    return PirHelpers.builtinApp2(DefaultFun.MkCons, wrappedArg, scope);
                },
                scopeType -> scopeType);

        // contains: recursive search with typed equality
        reg.register("ListType", "contains",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("contains() requires one argument. Usage: list.contains(element)");
                    var lt = (PirType.ListType) scopeType;
                    var targetType = argTypes.get(0);
                    return PirHelpers.generateListContains(scope, args.get(0), lt.elemType(), targetType);
                },
                scopeType -> new PirType.BoolType());

        // reverse: LetRec go(lst, acc) = if null(lst) then acc else go(tail(lst), mkCons(head(lst), acc))
        reg.register("ListType", "reverse",
                (scope, args, scopeType, argTypes) -> {
                    var lstVar = new PirTerm.Var("lst_rev", new PirType.ListType(new PirType.DataType()));
                    var accVar = new PirTerm.Var("acc_rev", new PirType.ListType(new PirType.DataType()));
                    var goVar = new PirTerm.Var("go_rev", new PirType.FunType(
                            new PirType.ListType(new PirType.DataType()),
                            new PirType.FunType(new PirType.ListType(new PirType.DataType()),
                                    new PirType.ListType(new PirType.DataType()))));

                    var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
                    var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
                    var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
                    var consExpr = PirHelpers.builtinApp2(DefaultFun.MkCons, headExpr, accVar);
                    var recurse = new PirTerm.App(new PirTerm.App(goVar, tailExpr), consExpr);
                    var body = new PirTerm.IfThenElse(nullCheck, accVar, recurse);
                    var goBody = new PirTerm.Lam("lst_rev", new PirType.ListType(new PirType.DataType()),
                            new PirTerm.Lam("acc_rev", new PirType.ListType(new PirType.DataType()), body));
                    var binding = new PirTerm.Binding("go_rev", goBody);
                    var nilData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                            new PirTerm.Const(Constant.unit()));
                    return new PirTerm.LetRec(List.of(binding),
                            new PirTerm.App(new PirTerm.App(goVar, scope), nilData));
                },
                scopeType -> scopeType);

        // concat: reverse self, then fold-prepend onto other
        // go(reversed, other) = if null(reversed) then other else go(tail(reversed), mkCons(head(reversed), other))
        reg.register("ListType", "concat",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("concat() requires one argument. Usage: list.concat(otherList)");
                    // First reverse the source list
                    var revLstVar = new PirTerm.Var("lst_crev", new PirType.ListType(new PirType.DataType()));
                    var revAccVar = new PirTerm.Var("acc_crev", new PirType.ListType(new PirType.DataType()));
                    var revGoVar = new PirTerm.Var("go_crev", new PirType.FunType(
                            new PirType.ListType(new PirType.DataType()),
                            new PirType.FunType(new PirType.ListType(new PirType.DataType()),
                                    new PirType.ListType(new PirType.DataType()))));
                    var revNull = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), revLstVar);
                    var revHead = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), revLstVar);
                    var revTail = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), revLstVar);
                    var revCons = PirHelpers.builtinApp2(DefaultFun.MkCons, revHead, revAccVar);
                    var revRecurse = new PirTerm.App(new PirTerm.App(revGoVar, revTail), revCons);
                    var revBody = new PirTerm.IfThenElse(revNull, revAccVar, revRecurse);
                    var revGoBody = new PirTerm.Lam("lst_crev", new PirType.ListType(new PirType.DataType()),
                            new PirTerm.Lam("acc_crev", new PirType.ListType(new PirType.DataType()), revBody));
                    var revBinding = new PirTerm.Binding("go_crev", revGoBody);
                    // reversed = go_crev(self, [])  then go_crev(reversed, other)
                    // Actually: go(reversed_self, other) does fold-prepend of reversed onto other, giving self ++ other
                    var nilData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                            new PirTerm.Const(Constant.unit()));
                    var reversed = new PirTerm.App(new PirTerm.App(revGoVar, scope), nilData);
                    var result = new PirTerm.App(new PirTerm.App(revGoVar, reversed), args.get(0));
                    return new PirTerm.LetRec(List.of(revBinding), result);
                },
                scopeType -> scopeType);

        // take(n): LetRec go(lst, n) = if n<=0 || null(lst) then nil else mkCons(head(lst), go(tail(lst), n-1))
        reg.register("ListType", "take",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("take() requires a count argument. Usage: list.take(n)");
                    var lstVar = new PirTerm.Var("lst_take", new PirType.ListType(new PirType.DataType()));
                    var nVar = new PirTerm.Var("n_take", new PirType.IntegerType());
                    var goVar = new PirTerm.Var("go_take", new PirType.FunType(
                            new PirType.ListType(new PirType.DataType()),
                            new PirType.FunType(new PirType.IntegerType(),
                                    new PirType.ListType(new PirType.DataType()))));

                    var zero = new PirTerm.Const(Constant.integer(BigInteger.ZERO));
                    var nIsZero = PirHelpers.builtinApp2(DefaultFun.LessThanEqualsInteger, nVar, zero);
                    var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
                    var nilData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                            new PirTerm.Const(Constant.unit()));

                    var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
                    var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
                    var decN = PirHelpers.builtinApp2(DefaultFun.SubtractInteger, nVar,
                            new PirTerm.Const(Constant.integer(BigInteger.ONE)));
                    var recurse = new PirTerm.App(new PirTerm.App(goVar, tailExpr), decN);
                    var consExpr = PirHelpers.builtinApp2(DefaultFun.MkCons, headExpr, recurse);

                    // if n<=0 then nil else if null(lst) then nil else mkCons(head, go(tail, n-1))
                    var body = new PirTerm.IfThenElse(nIsZero, nilData,
                            new PirTerm.IfThenElse(nullCheck, nilData, consExpr));
                    var goBody = new PirTerm.Lam("lst_take", new PirType.ListType(new PirType.DataType()),
                            new PirTerm.Lam("n_take", new PirType.IntegerType(), body));
                    var binding = new PirTerm.Binding("go_take", goBody);

                    return new PirTerm.LetRec(List.of(binding),
                            new PirTerm.App(new PirTerm.App(goVar, scope), args.get(0)));
                },
                scopeType -> scopeType);

        // drop(n): LetRec go(lst, n) = if n<=0 || null(lst) then lst else go(tail(lst), n-1)
        reg.register("ListType", "drop",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("drop() requires a count argument. Usage: list.drop(n)");
                    var lstVar = new PirTerm.Var("lst_drop", new PirType.ListType(new PirType.DataType()));
                    var nVar = new PirTerm.Var("n_drop", new PirType.IntegerType());
                    var goVar = new PirTerm.Var("go_drop", new PirType.FunType(
                            new PirType.ListType(new PirType.DataType()),
                            new PirType.FunType(new PirType.IntegerType(),
                                    new PirType.ListType(new PirType.DataType()))));

                    var zero = new PirTerm.Const(Constant.integer(BigInteger.ZERO));
                    var nIsZero = PirHelpers.builtinApp2(DefaultFun.LessThanEqualsInteger, nVar, zero);
                    var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
                    var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
                    var decN = PirHelpers.builtinApp2(DefaultFun.SubtractInteger, nVar,
                            new PirTerm.Const(Constant.integer(BigInteger.ONE)));
                    var recurse = new PirTerm.App(new PirTerm.App(goVar, tailExpr), decN);

                    var body = new PirTerm.IfThenElse(nIsZero, lstVar,
                            new PirTerm.IfThenElse(nullCheck, lstVar, recurse));
                    var goBody = new PirTerm.Lam("lst_drop", new PirType.ListType(new PirType.DataType()),
                            new PirTerm.Lam("n_drop", new PirType.IntegerType(), body));
                    var binding = new PirTerm.Binding("go_drop", goBody);
                    return new PirTerm.LetRec(List.of(binding),
                            new PirTerm.App(new PirTerm.App(goVar, scope), args.get(0)));
                },
                scopeType -> scopeType);

        // toArray: ListToArray(list) — PV11 only
        reg.register("ListType", "toArray",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.ListToArray), scope),
                scopeType -> new PirType.ArrayType(((PirType.ListType) scopeType).elemType()));
    }

    // --- Array methods (2): length, get ---

    private static void registerArrayMethods(TypeMethodRegistry reg) {
        // length: LengthOfArray(arr)
        reg.register("ArrayType", "length",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.LengthOfArray), scope),
                scopeType -> new PirType.IntegerType());

        // get(index): wrapDecode(IndexArray(arr, index), elemType)
        reg.register("ArrayType", "get",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("arr.get() requires an index argument. Usage: arr.get(0)");
                    var at = (PirType.ArrayType) scopeType;
                    var raw = PirHelpers.builtinApp2(DefaultFun.IndexArray, scope, args.get(0));
                    return PirHelpers.wrapDecode(raw, at.elemType());
                },
                scopeType -> ((PirType.ArrayType) scopeType).elemType());
    }

    // --- List HOF instance methods (5): map, filter, any, all, find ---

    private static void registerListHofMethods(TypeMethodRegistry reg) {
        // map(fn): apply fn to each element, return new list
        reg.register("ListType", "map",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("list.map() requires a function argument. Usage: list.map(x -> x + 1)");
                    return PirHofBuilders.map(scope, args.get(0));
                },
                scopeType -> new PirType.ListType(new PirType.DataType()));

        // filter(pred): keep elements where pred returns true
        reg.register("ListType", "filter",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("list.filter() requires a predicate argument. Usage: list.filter(x -> x > 0)");
                    return PirHofBuilders.filter(scope, args.get(0));
                },
                scopeType -> scopeType);

        // any(pred): return true if any element satisfies pred
        reg.register("ListType", "any",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("list.any() requires a predicate argument. Usage: list.any(x -> x > 0)");
                    return PirHofBuilders.any(scope, args.get(0));
                },
                scopeType -> new PirType.BoolType());

        // all(pred): return true if all elements satisfy pred
        reg.register("ListType", "all",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("list.all() requires a predicate argument. Usage: list.all(x -> x > 0)");
                    return PirHofBuilders.all(scope, args.get(0));
                },
                scopeType -> new PirType.BoolType());

        // find(pred): return first element matching pred as Optional
        reg.register("ListType", "find",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("list.find() requires a predicate argument. Usage: list.find(x -> x == target)");
                    return PirHofBuilders.find(scope, args.get(0));
                },
                scopeType -> new PirType.OptionalType(new PirType.DataType()));
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

    // --- Map methods (6): get, containsKey, size, isEmpty, keys, values ---

    private static void registerMapMethods(TypeMethodRegistry reg) {
        // get(key): search pair list, return value directly (crash on miss)
        reg.register("MapType", "get",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("map.get() requires a key argument. Usage: map.get(key). Note: crashes on missing key; use map.lookup(key) for Optional result");
                    var mt = (PirType.MapType) scopeType;
                    var keyArg = PirHelpers.wrapEncode(args.get(0),
                            argTypes.isEmpty() ? new PirType.DataType() : argTypes.get(0));
                    // On miss: crash by taking HeadList of an empty list
                    var crashOnMiss = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList),
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                                    new PirTerm.Const(Constant.unit())));
                    return PirHelpers.pairListSearch("get", scope, keyArg, crashOnMiss,
                            (h, k, goTail) -> {
                                var fstH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), h);
                                var sndH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), h);
                                var eqCheck = PirHelpers.builtinApp2(DefaultFun.EqualsData, fstH, k);
                                return new PirTerm.IfThenElse(eqCheck,
                                        PirHelpers.wrapDecode(sndH, mt.valueType()), goTail);
                            });
                },
                scopeType -> ((PirType.MapType) scopeType).valueType());

        // lookup(key): search pair list, return Optional(value) on match
        reg.register("MapType", "lookup",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("map.lookup() requires a key argument. Usage: map.lookup(key). Returns Optional<V>");
                    var keyArg = PirHelpers.wrapEncode(args.get(0),
                            argTypes.isEmpty() ? new PirType.DataType() : argTypes.get(0));
                    return PirHelpers.pairListSearch("lk", scope, keyArg, PirHelpers.mkNone(),
                            (h, k, goTail) -> {
                                var fstH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), h);
                                var sndH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), h);
                                var eqCheck = PirHelpers.builtinApp2(DefaultFun.EqualsData, fstH, k);
                                return new PirTerm.IfThenElse(eqCheck, PirHelpers.mkSome(sndH), goTail);
                            });
                },
                scopeType -> new PirType.OptionalType(((PirType.MapType) scopeType).valueType()));

        // containsKey(key): search pair list, return true on match
        reg.register("MapType", "containsKey",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("map.containsKey() requires a key argument. Usage: map.containsKey(key)");
                    var keyArg = PirHelpers.wrapEncode(args.get(0),
                            argTypes.isEmpty() ? new PirType.DataType() : argTypes.get(0));
                    return PirHelpers.pairListSearch("ck", scope, keyArg,
                            new PirTerm.Const(Constant.bool(false)),
                            (h, k, goTail) -> {
                                var fstH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), h);
                                var eqCheck = PirHelpers.builtinApp2(DefaultFun.EqualsData, fstH, k);
                                return new PirTerm.IfThenElse(eqCheck,
                                        new PirTerm.Const(Constant.bool(true)), goTail);
                            });
                },
                scopeType -> new PirType.BoolType());

        // size(): foldl-based count of pair list
        reg.register("MapType", "size",
                (scope, args, scopeType, argTypes) ->
                        PirHelpers.generateListLength(scope),
                scopeType -> new PirType.IntegerType());

        // isEmpty(): NullList(pairList)
        reg.register("MapType", "isEmpty",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), scope),
                scopeType -> new PirType.BoolType());

        // head(): HeadList(pairList) — returns native pair, no wrapDecode needed
        reg.register("MapType", "head",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), scope),
                scopeType -> {
                    var mt = (PirType.MapType) scopeType;
                    return new PirType.PairType(mt.keyType(), mt.valueType());
                });

        // tail(): TailList(pairList) — returns same MapType
        reg.register("MapType", "tail",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), scope),
                scopeType -> scopeType);

        // keys(): foldl collecting fstPair from each pair
        reg.register("MapType", "keys",
                (scope, args, scopeType, argTypes) -> {
                    var mt = (PirType.MapType) scopeType;
                    // scope is already a pair list
                    // foldl(\acc pair -> MkCons(FstPair(pair), acc), MkNilData, scope)
                    var accVar = new PirTerm.Var("acc__keys", new PirType.ListType(new PirType.DataType()));
                    var pairVar = new PirTerm.Var("p__keys", new PirType.DataType());
                    var fstExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), pairVar);
                    var consExpr = PirHelpers.builtinApp2(DefaultFun.MkCons, fstExpr, accVar);
                    var foldFn = new PirTerm.Lam("acc__keys", new PirType.ListType(new PirType.DataType()),
                            new PirTerm.Lam("p__keys", new PirType.DataType(), consExpr));
                    var nilData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                            new PirTerm.Const(Constant.unit()));
                    return PirHelpers.generateFoldl(foldFn, nilData, scope);
                },
                scopeType -> new PirType.ListType(((PirType.MapType) scopeType).keyType()));

        // values(): foldl collecting sndPair from each pair
        reg.register("MapType", "values",
                (scope, args, scopeType, argTypes) -> {
                    var mt = (PirType.MapType) scopeType;
                    // scope is already a pair list
                    // foldl(\acc pair -> MkCons(SndPair(pair), acc), MkNilData, scope)
                    var accVar = new PirTerm.Var("acc__vals", new PirType.ListType(new PirType.DataType()));
                    var pairVar = new PirTerm.Var("p__vals", new PirType.DataType());
                    var sndExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), pairVar);
                    var consExpr = PirHelpers.builtinApp2(DefaultFun.MkCons, sndExpr, accVar);
                    var foldFn = new PirTerm.Lam("acc__vals", new PirType.ListType(new PirType.DataType()),
                            new PirTerm.Lam("p__vals", new PirType.DataType(), consExpr));
                    var nilData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                            new PirTerm.Const(Constant.unit()));
                    return PirHelpers.generateFoldl(foldFn, nilData, scope);
                },
                scopeType -> new PirType.ListType(((PirType.MapType) scopeType).valueType()));

        // insert(key, value): MkCons(MkPairData(key, value), pairList) — returns pair list
        reg.register("MapType", "insert",
                (scope, args, scopeType, argTypes) -> {
                    if (args.size() < 2) throw new CompilerException("map.insert() requires key and value arguments. Usage: map.insert(key, value)");
                    var mt = (PirType.MapType) scopeType;
                    var keyArg = PirHelpers.wrapEncode(args.get(0), argTypes.size() >= 1 ? argTypes.get(0) : new PirType.DataType());
                    var valArg = PirHelpers.wrapEncode(args.get(1), argTypes.size() >= 2 ? argTypes.get(1) : new PirType.DataType());
                    var pair = PirHelpers.builtinApp2(DefaultFun.MkPairData, keyArg, valArg);
                    // scope is already a pair list — cons directly
                    return PirHelpers.builtinApp2(DefaultFun.MkCons, pair, scope);
                },
                scopeType -> scopeType);

        // delete(key): filter out matching key from pair list
        reg.register("MapType", "delete",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("map.delete() requires a key argument. Usage: map.delete(key)");
                    var keyArg = PirHelpers.wrapEncode(args.get(0),
                            argTypes.isEmpty() ? new PirType.DataType() : argTypes.get(0));
                    return PirHelpers.pairListSearch("del", scope, keyArg, PirHelpers.mkNilPairData(),
                            (h, k, goTail) -> {
                                var fstH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), h);
                                var eqCheck = PirHelpers.builtinApp2(DefaultFun.EqualsData, fstH, k);
                                // Match: skip pair (recurse on tail). No match: keep pair (cons + recurse)
                                return new PirTerm.IfThenElse(eqCheck, goTail,
                                        PirHelpers.builtinApp2(DefaultFun.MkCons, h, goTail));
                            });
                },
                scopeType -> scopeType);
    }

    // --- Pair methods (2): key, value ---

    private static void registerPairMethods(TypeMethodRegistry reg) {
        // key(): FstPair(pair) + wrapDecode
        reg.register("PairType", "key",
                (scope, args, scopeType, argTypes) -> {
                    var pt = (PirType.PairType) scopeType;
                    var raw = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), scope);
                    return PirHelpers.wrapDecode(raw, pt.first());
                },
                scopeType -> ((PirType.PairType) scopeType).first());

        // value(): SndPair(pair) + wrapDecode
        reg.register("PairType", "value",
                (scope, args, scopeType, argTypes) -> {
                    var pt = (PirType.PairType) scopeType;
                    var raw = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), scope);
                    return PirHelpers.wrapDecode(raw, pt.second());
                },
                scopeType -> ((PirType.PairType) scopeType).second());
    }

    // --- Value instance methods (3): lovelaceOf, assetOf, isEmpty ---
    // Registered under named key "Value" so they only match RecordType(name="Value", ...)

    private static void registerValueMethods(TypeMethodRegistry reg) {
        // lovelaceOf(): extract lovelace from Value (Map<BS, Map<BS, Int>>)
        // Value → UnMapData → HeadList → SndPair → UnMapData → HeadList → SndPair → UnIData
        reg.register("Value", "lovelaceOf",
                (scope, args, scopeType, argTypes) -> {
                    var innerMap = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), scope);
                    var firstPair = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), innerMap);
                    var tokenMapData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), firstPair);
                    var tokenPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), tokenMapData);
                    var firstTokenPair = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), tokenPairs);
                    var amountData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), firstTokenPair);
                    return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), amountData);
                },
                scopeType -> new PirType.IntegerType());

        // isEmpty(): NullList(UnMapData(value))
        reg.register("Value", "isEmpty",
                (scope, args, scopeType, argTypes) ->
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList),
                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), scope)),
                scopeType -> new PirType.BoolType());

        // containsPolicy(policyId): check if policy exists in Value's outer map
        reg.register("Value", "containsPolicy",
                (scope, args, scopeType, argTypes) -> {
                    if (args.isEmpty()) throw new CompilerException("value.containsPolicy() requires a policyId argument. Usage: value.containsPolicy(policyId)");
                    var pairList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), scope);
                    var keyArg = PirHelpers.wrapEncode(args.get(0),
                            argTypes.isEmpty() ? new PirType.DataType() : argTypes.get(0));
                    return PirHelpers.pairListSearch("cp", pairList, keyArg,
                            new PirTerm.Const(Constant.bool(false)),
                            (h, k, goTail) -> {
                                var fstH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), h);
                                var eqCheck = PirHelpers.builtinApp2(DefaultFun.EqualsData, fstH, k);
                                return new PirTerm.IfThenElse(eqCheck,
                                        new PirTerm.Const(Constant.bool(true)), goTail);
                            });
                },
                scopeType -> new PirType.BoolType());

        // assetOf(policyId, tokenName): LetRec search in nested maps
        reg.register("Value", "assetOf",
                (scope, args, scopeType, argTypes) -> {
                    if (args.size() < 2) throw new CompilerException("value.assetOf() requires policyId and tokenName arguments. Usage: value.assetOf(policyId, tokenName)");
                    var policyArg = PirHelpers.wrapEncode(args.get(0), argTypes.size() >= 1 ? argTypes.get(0) : new PirType.DataType());
                    var tokenArg = PirHelpers.wrapEncode(args.get(1), argTypes.size() >= 2 ? argTypes.get(1) : new PirType.DataType());

                    // Let-bind value, policyId, tokenName
                    var valVar = new PirTerm.Var("v__ao", new PirType.DataType());
                    var polVar = new PirTerm.Var("pol__ao", new PirType.DataType());
                    var tokVar = new PirTerm.Var("tok__ao", new PirType.DataType());

                    // Outer loop: search for matching policy in UnMapData(value)
                    var outerPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), valVar);
                    var outerPairsVar = new PirTerm.Var("ops__ao", new PirType.ListType(
                            new PirType.PairType(new PirType.DataType(), new PirType.DataType())));

                    // Inner loop: search for matching token in the inner map
                    var innerLstVar = new PirTerm.Var("ils__ao", new PirType.ListType(
                            new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
                    var innerGoVar = new PirTerm.Var("igo__ao", new PirType.FunType(
                            new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                            new PirType.IntegerType()));

                    var innerNull = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), innerLstVar);
                    var innerHead = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), innerLstVar);
                    var innerHVar = new PirTerm.Var("ih__ao", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
                    var innerFst = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), innerHVar);
                    var innerSnd = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), innerHVar);
                    var innerEq = PirHelpers.builtinApp2(DefaultFun.EqualsData, innerFst, tokVar);
                    var innerTail = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), innerLstVar);
                    var innerRecurse = new PirTerm.App(innerGoVar, innerTail);
                    var innerAmount = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), innerSnd);
                    var innerIf = new PirTerm.IfThenElse(innerEq, innerAmount, innerRecurse);
                    var innerLet = new PirTerm.Let("ih__ao", innerHead, innerIf);
                    var innerOuter = new PirTerm.IfThenElse(innerNull,
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO)), innerLet);
                    var innerBody = new PirTerm.Lam("ils__ao", new PirType.ListType(
                            new PirType.PairType(new PirType.DataType(), new PirType.DataType())), innerOuter);
                    var innerBinding = new PirTerm.Binding("igo__ao", innerBody);

                    // Outer loop
                    var outerLstVar = new PirTerm.Var("ols__ao", new PirType.ListType(
                            new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
                    var outerGoVar = new PirTerm.Var("ogo__ao", new PirType.FunType(
                            new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                            new PirType.IntegerType()));

                    var outerNull = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), outerLstVar);
                    var outerHead = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), outerLstVar);
                    var outerHVar = new PirTerm.Var("oh__ao", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
                    var outerFst = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), outerHVar);
                    var outerSnd = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), outerHVar);
                    var outerEq = PirHelpers.builtinApp2(DefaultFun.EqualsData, outerFst, polVar);
                    var outerTail = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), outerLstVar);
                    var outerRecurse = new PirTerm.App(outerGoVar, outerTail);

                    // When policy matches, search inner map
                    var innerMapPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), outerSnd);
                    var innerSearch = new PirTerm.LetRec(List.of(innerBinding),
                            new PirTerm.App(innerGoVar, innerMapPairs));

                    var outerIf = new PirTerm.IfThenElse(outerEq, innerSearch, outerRecurse);
                    var outerLet = new PirTerm.Let("oh__ao", outerHead, outerIf);
                    var outerOuter = new PirTerm.IfThenElse(outerNull,
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO)), outerLet);
                    var outerBody = new PirTerm.Lam("ols__ao", new PirType.ListType(
                            new PirType.PairType(new PirType.DataType(), new PirType.DataType())), outerOuter);
                    var outerBinding = new PirTerm.Binding("ogo__ao", outerBody);

                    var search = new PirTerm.LetRec(List.of(outerBinding),
                            new PirTerm.App(outerGoVar, outerPairsVar));

                    return new PirTerm.Let("v__ao", scope,
                            new PirTerm.Let("pol__ao", policyArg,
                                    new PirTerm.Let("tok__ao", tokenArg,
                                            new PirTerm.Let("ops__ao", outerPairs, search))));
                },
                scopeType -> new PirType.IntegerType());
    }
}
