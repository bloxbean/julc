package com.bloxbean.cardano.plutus.compiler.pir;

import com.bloxbean.cardano.plutus.compiler.CompilerException;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;

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
}
