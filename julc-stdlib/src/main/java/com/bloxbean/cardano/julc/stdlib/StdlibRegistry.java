package com.bloxbean.cardano.julc.stdlib;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.pir.StdlibLookup;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry mapping method names to PIR term builders.
 * <p>
 * The registry maps (className, methodName) pairs to {@link PirTermBuilder} instances
 * that produce PIR terms for standard library functions. This allows the compiler
 * to replace calls to stdlib methods with their PIR implementations.
 * <p>
 * Usage:
 * <pre>{@code
 * StdlibRegistry registry = StdlibRegistry.defaultRegistry();
 * Optional<PirTerm> term = registry.lookup("ListsLib", "isEmpty", List.of(listTerm));
 * }</pre>
 */
public final class StdlibRegistry implements StdlibLookup {

    /**
     * Functional interface for building PIR terms from argument terms.
     */
    @FunctionalInterface
    public interface PirTermBuilder {
        /**
         * Build a PIR term from the given arguments.
         *
         * @param args the argument PIR terms
         * @return the resulting PIR term
         */
        PirTerm build(List<PirTerm> args);
    }

    private final Map<String, PirTermBuilder> registry = new HashMap<>();

    /**
     * Creates a new empty registry.
     */
    public StdlibRegistry() {}

    /**
     * Register a term builder for a given class and method name.
     *
     * @param className  the simple class name (e.g., "ListsLib")
     * @param methodName the method name (e.g., "isEmpty")
     * @param builder    the PIR term builder
     */
    public void register(String className, String methodName, PirTermBuilder builder) {
        String key = className + "." + methodName;
        registry.put(key, builder);
    }

    /**
     * Look up a PIR term builder and invoke it with the given arguments.
     *
     * @param className  the simple class name
     * @param methodName the method name
     * @param args       the argument PIR terms
     * @return the built PIR term, or empty if no builder is registered
     */
    @Override
    public Optional<PirTerm> lookup(String className, String methodName, List<PirTerm> args) {
        String key = className + "." + methodName;
        PirTermBuilder builder = registry.get(key);
        if (builder == null) {
            return Optional.empty();
        }
        return Optional.of(builder.build(args));
    }

    /**
     * Look up a PIR term builder by key.
     *
     * @param className  the simple class name
     * @param methodName the method name
     * @return the term builder, or empty if not registered
     */
    public Optional<PirTermBuilder> lookupBuilder(String className, String methodName) {
        String key = className + "." + methodName;
        return Optional.ofNullable(registry.get(key));
    }

    /**
     * Check if a builder is registered for the given class and method.
     *
     * @param className  the simple class name
     * @param methodName the method name
     * @return true if a builder is registered
     */
    public boolean contains(String className, String methodName) {
        return registry.containsKey(className + "." + methodName);
    }

    @Override
    public boolean hasMethodsForClass(String className) {
        String prefix = className + ".";
        return registry.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    /**
     * Returns the number of registered builders.
     */
    public int size() {
        return registry.size();
    }

    /**
     * Creates the default registry with all standard library functions registered.
     *
     * @return a fully populated StdlibRegistry
     */
    public static StdlibRegistry defaultRegistry() {
        var reg = new StdlibRegistry();
        registerBuiltins(reg);
        registerListsLibHof(reg);
        registerContextsTrace(reg);
        registerJavaMathDelegates(reg);
        registerCollectionFactories(reg);
        registerLedgerTypeFactories(reg);
        return reg;
    }

    /**
     * Register raw UPLC builtin operations under the "Builtins" class name.
     * These are the bridge between Java stdlib sources and the UPLC machine.
     */
    private static void registerBuiltins(StdlibRegistry reg) {
        // List operations
        reg.register("Builtins", "headList", args -> {
            requireArgs("Builtins.headList", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), args.get(0));
        });
        reg.register("Builtins", "tailList", args -> {
            requireArgs("Builtins.tailList", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), args.get(0));
        });
        reg.register("Builtins", "nullList", args -> {
            requireArgs("Builtins.nullList", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), args.get(0));
        });
        reg.register("Builtins", "mkCons", args -> {
            requireArgs("Builtins.mkCons", args, 2);
            return new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), args.get(0)),
                    args.get(1));
        });
        reg.register("Builtins", "mkNilData", args -> {
            requireArgs("Builtins.mkNilData", args, 0);
            return new PirTerm.App(
                    new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));
        });

        // Pair operations
        reg.register("Builtins", "fstPair", args -> {
            requireArgs("Builtins.fstPair", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), args.get(0));
        });
        reg.register("Builtins", "sndPair", args -> {
            requireArgs("Builtins.sndPair", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), args.get(0));
        });
        reg.register("Builtins", "mkPairData", args -> {
            requireArgs("Builtins.mkPairData", args, 2);
            return new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkPairData), args.get(0)),
                    args.get(1));
        });
        reg.register("Builtins", "mkNilPairData", args -> {
            requireArgs("Builtins.mkNilPairData", args, 0);
            return new PirTerm.App(
                    new PirTerm.Builtin(DefaultFun.MkNilPairData),
                    new PirTerm.Const(Constant.unit()));
        });

        // Data encode
        reg.register("Builtins", "constrData", args -> {
            requireArgs("Builtins.constrData", args, 2);
            return new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData), args.get(0)),
                    args.get(1));
        });
        reg.register("Builtins", "iData", args -> {
            requireArgs("Builtins.iData", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData), args.get(0));
        });
        reg.register("Builtins", "bData", args -> {
            requireArgs("Builtins.bData", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.BData), args.get(0));
        });
        reg.register("Builtins", "listData", args -> {
            requireArgs("Builtins.listData", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.ListData), args.get(0));
        });
        reg.register("Builtins", "mapData", args -> {
            requireArgs("Builtins.mapData", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.MapData), args.get(0));
        });

        // Data decode
        reg.register("Builtins", "unConstrData", args -> {
            requireArgs("Builtins.unConstrData", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), args.get(0));
        });
        reg.register("Builtins", "unIData", args -> {
            requireArgs("Builtins.unIData", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), args.get(0));
        });
        reg.register("Builtins", "unBData", args -> {
            requireArgs("Builtins.unBData", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), args.get(0));
        });
        reg.register("Builtins", "unListData", args -> {
            requireArgs("Builtins.unListData", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), args.get(0));
        });
        reg.register("Builtins", "unMapData", args -> {
            requireArgs("Builtins.unMapData", args, 1);
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), args.get(0));
        });

        // Data comparison
        reg.register("Builtins", "equalsData", args -> {
            requireArgs("Builtins.equalsData", args, 2);
            return new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsData), args.get(0)),
                    args.get(1));
        });

        // Error / Trace
        reg.register("Builtins", "error", args -> {
            requireArgs("Builtins.error", args, 0);
            return new PirTerm.Error(new com.bloxbean.cardano.julc.compiler.pir.PirType.DataType());
        });
        reg.register("Builtins", "trace", args -> {
            requireArgs("Builtins.trace", args, 2);
            return new PirTerm.Trace(args.get(0), args.get(1));
        });

        // ByteString operations
        reg.register("Builtins", "indexByteString", args -> {
            requireArgs("Builtins.indexByteString", args, 2);
            return builtinApp2(DefaultFun.IndexByteString, args.get(0), args.get(1));
        });
        reg.register("Builtins", "consByteString", args -> {
            requireArgs("Builtins.consByteString", args, 2);
            return builtinApp2(DefaultFun.ConsByteString, args.get(0), args.get(1));
        });
        reg.register("Builtins", "sliceByteString", args -> {
            requireArgs("Builtins.sliceByteString", args, 3);
            return builtinApp3(DefaultFun.SliceByteString, args.get(0), args.get(1), args.get(2));
        });
        reg.register("Builtins", "lengthOfByteString", args -> {
            requireArgs("Builtins.lengthOfByteString", args, 1);
            return builtinApp1(DefaultFun.LengthOfByteString, args.get(0));
        });
        reg.register("Builtins", "appendByteString", args -> {
            requireArgs("Builtins.appendByteString", args, 2);
            return builtinApp2(DefaultFun.AppendByteString, args.get(0), args.get(1));
        });
        reg.register("Builtins", "equalsByteString", args -> {
            requireArgs("Builtins.equalsByteString", args, 2);
            return builtinApp2(DefaultFun.EqualsByteString, args.get(0), args.get(1));
        });
        reg.register("Builtins", "lessThanByteString", args -> {
            requireArgs("Builtins.lessThanByteString", args, 2);
            return builtinApp2(DefaultFun.LessThanByteString, args.get(0), args.get(1));
        });
        reg.register("Builtins", "lessThanEqualsByteString", args -> {
            requireArgs("Builtins.lessThanEqualsByteString", args, 2);
            return builtinApp2(DefaultFun.LessThanEqualsByteString, args.get(0), args.get(1));
        });
        reg.register("Builtins", "integerToByteString", args -> {
            requireArgs("Builtins.integerToByteString", args, 3);
            return builtinApp3(DefaultFun.IntegerToByteString, args.get(0), args.get(1), args.get(2));
        });
        reg.register("Builtins", "byteStringToInteger", args -> {
            requireArgs("Builtins.byteStringToInteger", args, 2);
            return builtinApp2(DefaultFun.ByteStringToInteger, args.get(0), args.get(1));
        });
        reg.register("Builtins", "encodeUtf8", args -> {
            requireArgs("Builtins.encodeUtf8", args, 1);
            return builtinApp1(DefaultFun.EncodeUtf8, args.get(0));
        });
        reg.register("Builtins", "decodeUtf8", args -> {
            requireArgs("Builtins.decodeUtf8", args, 1);
            return builtinApp1(DefaultFun.DecodeUtf8, args.get(0));
        });
        reg.register("Builtins", "serialiseData", args -> {
            requireArgs("Builtins.serialiseData", args, 1);
            return builtinApp1(DefaultFun.SerialiseData, args.get(0));
        });
        reg.register("Builtins", "replicateByte", args -> {
            requireArgs("Builtins.replicateByte", args, 2);
            return builtinApp2(DefaultFun.ReplicateByte, args.get(0), args.get(1));
        });
        reg.register("Builtins", "emptyByteString", args -> {
            requireArgs("Builtins.emptyByteString", args, 0);
            return new PirTerm.Const(Constant.byteString(new byte[0]));
        });

        // Crypto operations
        reg.register("Builtins", "sha2_256", args -> {
            requireArgs("Builtins.sha2_256", args, 1);
            return builtinApp1(DefaultFun.Sha2_256, args.get(0));
        });
        reg.register("Builtins", "blake2b_256", args -> {
            requireArgs("Builtins.blake2b_256", args, 1);
            return builtinApp1(DefaultFun.Blake2b_256, args.get(0));
        });
        reg.register("Builtins", "verifyEd25519Signature", args -> {
            requireArgs("Builtins.verifyEd25519Signature", args, 3);
            return builtinApp3(DefaultFun.VerifyEd25519Signature, args.get(0), args.get(1), args.get(2));
        });
        reg.register("Builtins", "sha3_256", args -> {
            requireArgs("Builtins.sha3_256", args, 1);
            return builtinApp1(DefaultFun.Sha3_256, args.get(0));
        });
        reg.register("Builtins", "blake2b_224", args -> {
            requireArgs("Builtins.blake2b_224", args, 1);
            return builtinApp1(DefaultFun.Blake2b_224, args.get(0));
        });
        reg.register("Builtins", "keccak_256", args -> {
            requireArgs("Builtins.keccak_256", args, 1);
            return builtinApp1(DefaultFun.Keccak_256, args.get(0));
        });
        reg.register("Builtins", "verifyEcdsaSecp256k1Signature", args -> {
            requireArgs("Builtins.verifyEcdsaSecp256k1Signature", args, 3);
            return builtinApp3(DefaultFun.VerifyEcdsaSecp256k1Signature, args.get(0), args.get(1), args.get(2));
        });
        reg.register("Builtins", "verifySchnorrSecp256k1Signature", args -> {
            requireArgs("Builtins.verifySchnorrSecp256k1Signature", args, 3);
            return builtinApp3(DefaultFun.VerifySchnorrSecp256k1Signature, args.get(0), args.get(1), args.get(2));
        });
        reg.register("Builtins", "ripemd_160", args -> {
            requireArgs("Builtins.ripemd_160", args, 1);
            return builtinApp1(DefaultFun.Ripemd_160, args.get(0));
        });

        // Bitwise operations
        reg.register("Builtins", "andByteString", args -> {
            requireArgs("Builtins.andByteString", args, 3);
            return builtinApp3(DefaultFun.AndByteString, args.get(0), args.get(1), args.get(2));
        });
        reg.register("Builtins", "orByteString", args -> {
            requireArgs("Builtins.orByteString", args, 3);
            return builtinApp3(DefaultFun.OrByteString, args.get(0), args.get(1), args.get(2));
        });
        reg.register("Builtins", "xorByteString", args -> {
            requireArgs("Builtins.xorByteString", args, 3);
            return builtinApp3(DefaultFun.XorByteString, args.get(0), args.get(1), args.get(2));
        });
        reg.register("Builtins", "complementByteString", args -> {
            requireArgs("Builtins.complementByteString", args, 1);
            return builtinApp1(DefaultFun.ComplementByteString, args.get(0));
        });
        reg.register("Builtins", "readBit", args -> {
            requireArgs("Builtins.readBit", args, 2);
            return builtinApp2(DefaultFun.ReadBit, args.get(0), args.get(1));
        });
        reg.register("Builtins", "writeBits", args -> {
            requireArgs("Builtins.writeBits", args, 3);
            return builtinApp3(DefaultFun.WriteBits, args.get(0), args.get(1), args.get(2));
        });
        reg.register("Builtins", "shiftByteString", args -> {
            requireArgs("Builtins.shiftByteString", args, 2);
            return builtinApp2(DefaultFun.ShiftByteString, args.get(0), args.get(1));
        });
        reg.register("Builtins", "rotateByteString", args -> {
            requireArgs("Builtins.rotateByteString", args, 2);
            return builtinApp2(DefaultFun.RotateByteString, args.get(0), args.get(1));
        });
        reg.register("Builtins", "countSetBits", args -> {
            requireArgs("Builtins.countSetBits", args, 1);
            return builtinApp1(DefaultFun.CountSetBits, args.get(0));
        });
        reg.register("Builtins", "findFirstSetBit", args -> {
            requireArgs("Builtins.findFirstSetBit", args, 1);
            return builtinApp1(DefaultFun.FindFirstSetBit, args.get(0));
        });

        // Data decomposition helpers
        reg.register("Builtins", "constrTag", args -> {
            requireArgs("Builtins.constrTag", args, 1);
            var unconstr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), args.get(0));
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), unconstr);
        });
        reg.register("Builtins", "constrFields", args -> {
            requireArgs("Builtins.constrFields", args, 1);
            var unconstr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), args.get(0));
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), unconstr);
        });

        // Math operations
        reg.register("Builtins", "expModInteger", args -> {
            requireArgs("Builtins.expModInteger", args, 3);
            return builtinApp3(DefaultFun.ExpModInteger, args.get(0), args.get(1), args.get(2));
        });
    }

    /**
     * Register ListsLib HOF methods that require lambda/LetRec support.
     * These cannot be compiled from @OnchainLibrary Java source.
     * Non-HOF methods are compiled from Java source in onchain/ListsLib.java.
     */
    private static void registerListsLibHof(StdlibRegistry reg) {
        reg.register("ListsLib", "any", args -> {
            requireArgs("ListsLib.any", args, 2);
            return ListsLibHof.any(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "all", args -> {
            requireArgs("ListsLib.all", args, 2);
            return ListsLibHof.all(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "find", args -> {
            requireArgs("ListsLib.find", args, 2);
            return ListsLibHof.find(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "foldl", args -> {
            requireArgs("ListsLib.foldl", args, 3);
            return ListsLibHof.foldl(args.get(0), args.get(1), args.get(2));
        });

        reg.register("ListsLib", "map", args -> {
            requireArgs("ListsLib.map", args, 2);
            return ListsLibHof.map(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "filter", args -> {
            requireArgs("ListsLib.filter", args, 2);
            return ListsLibHof.filter(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "zip", args -> {
            requireArgs("ListsLib.zip", args, 2);
            return ListsLibHof.zip(args.get(0), args.get(1));
        });
    }

    /**
     * Register ContextsLib.trace as inline PIR.
     * This method uses UPLC Text type and cannot be compiled from @OnchainLibrary Java source.
     */
    private static void registerContextsTrace(StdlibRegistry reg) {
        reg.register("ContextsLib", "trace", args -> {
            requireArgs("ContextsLib.trace", args, 1);
            return new PirTerm.Trace(args.get(0), new PirTerm.Const(Constant.unit()));
        });
    }

    /**
     * Delegates java.lang.Math static methods to inline PIR equivalents.
     * Allows developers to write {@code Math.abs(x)} instead of {@code MathLib.abs(x)}.
     */
    private static void registerJavaMathDelegates(StdlibRegistry reg) {
        // abs(x) = IfThenElse(LessThanInteger(x, 0), SubtractInteger(0, x), x)
        reg.register("Math", "abs", args -> {
            requireArgs("Math.abs", args, 1);
            var x = args.get(0);
            var zero = new PirTerm.Const(Constant.integer(BigInteger.ZERO));
            var ltZero = builtinApp2(DefaultFun.LessThanInteger, x, zero);
            var negX = builtinApp2(DefaultFun.SubtractInteger, zero, x);
            return new PirTerm.IfThenElse(ltZero, negX, x);
        });

        // max(a, b) = IfThenElse(LessThanInteger(a, b), b, a)
        reg.register("Math", "max", args -> {
            requireArgs("Math.max", args, 2);
            var lt = builtinApp2(DefaultFun.LessThanInteger, args.get(0), args.get(1));
            return new PirTerm.IfThenElse(lt, args.get(1), args.get(0));
        });

        // min(a, b) = IfThenElse(LessThanEqualsInteger(a, b), a, b)
        reg.register("Math", "min", args -> {
            requireArgs("Math.min", args, 2);
            var lte = builtinApp2(DefaultFun.LessThanEqualsInteger, args.get(0), args.get(1));
            return new PirTerm.IfThenElse(lte, args.get(0), args.get(1));
        });
    }

    /**
     * Register JulcList factory methods as compiler intrinsics.
     * These allow on-chain code to create lists without going through ListsLib.
     */
    private static void registerCollectionFactories(StdlibRegistry reg) {
        // JulcList.empty() → MkNilData(unit)
        reg.register("JulcList", "empty", args -> {
            requireArgs("JulcList.empty", args, 0);
            return new PirTerm.App(
                    new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));
        });

        // JulcList.of(a, b, c) → MkCons(a, MkCons(b, MkCons(c, MkNilData(unit))))
        reg.register("JulcList", "of", args -> {
            PirTerm result = new PirTerm.App(
                    new PirTerm.Builtin(DefaultFun.MkNilData),
                    new PirTerm.Const(Constant.unit()));
            for (int i = args.size() - 1; i >= 0; i--) {
                result = builtinApp2(DefaultFun.MkCons, args.get(i), result);
            }
            return result;
        });
    }

    /**
     * Register .of() factory methods for ledger hash types.
     * On-chain, these are identity — the byte[] is already the underlying data.
     */
    private static void registerLedgerTypeFactories(StdlibRegistry reg) {
        for (String type : List.of("PubKeyHash", "ScriptHash", "ValidatorHash",
                                    "PolicyId", "TokenName", "DatumHash", "TxId")) {
            reg.register(type, "of", args -> {
                requireArgs(type + ".of", args, 1);
                return args.get(0);
            });
        }
    }

    private static void requireArgs(String method, List<PirTerm> args, int expected) {
        if (args.size() != expected) {
            throw new IllegalArgumentException(
                    method + " expects " + expected + " arguments, got " + args.size());
        }
    }

    private static PirTerm builtinApp1(DefaultFun fun, PirTerm arg) {
        return new PirTerm.App(new PirTerm.Builtin(fun), arg);
    }

    private static PirTerm builtinApp2(DefaultFun fun, PirTerm a, PirTerm b) {
        return new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(fun), a), b);
    }

    private static PirTerm builtinApp3(DefaultFun fun, PirTerm a, PirTerm b, PirTerm c) {
        return new PirTerm.App(
                new PirTerm.App(
                        new PirTerm.App(new PirTerm.Builtin(fun), a),
                        b),
                c);
    }
}
