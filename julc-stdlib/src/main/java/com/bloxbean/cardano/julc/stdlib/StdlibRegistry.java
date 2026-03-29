package com.bloxbean.cardano.julc.stdlib;

import com.bloxbean.cardano.julc.compiler.pir.PirHelpers;
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

    /** Package prefix for stdlib root classes (e.g., Builtins). */
    private static final String PKG = "com.bloxbean.cardano.julc.stdlib.";
    /** Package prefix for stdlib lib sub-package classes (e.g., ListsLib). */
    private static final String LIB = PKG + "lib.";

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

    @Override
    public Optional<PirTerm> lookup(String className, String methodName,
                                      List<PirTerm> args, List<PirType> argTypes) {
        // Optional.of(x) needs arg type info to properly encode the value
        if (isOptionalClass(className) && methodName.equals("of") && args.size() == 1) {
            var elemType = (argTypes != null && !argTypes.isEmpty()) ? argTypes.get(0) : new PirType.DataType();
            var recordType = new PirType.RecordType("Optional",
                    List.of(new PirType.Field("value", elemType)));
            return Optional.of(new PirTerm.DataConstr(0, recordType, List.of(args.get(0))));
        }
        // ListsLib.prepend(list, elem) — wrap elem to Data before MkCons
        if (isListsLibClass(className) && methodName.equals("prepend") && args.size() == 2) {
            var wrappedElem = PirHelpers.wrapEncode(args.get(1), safeArgType(argTypes, 1));
            return Optional.of(builtinApp2(DefaultFun.MkCons, wrappedElem, args.get(0)));
        }

        // ListsLib.contains(list, target) — wrap target, search with EqualsData
        if (isListsLibClass(className) && methodName.equals("contains") && args.size() == 2) {
            var wrappedTarget = PirHelpers.wrapEncode(args.get(1), safeArgType(argTypes, 1));
            return Optional.of(PirHelpers.listSearch("con", args.get(0), wrappedTarget,
                    new PirTerm.Const(Constant.bool(false)),
                    (elem, t, recurse) -> {
                        var eq = builtinApp2(DefaultFun.EqualsData, elem, t);
                        return new PirTerm.IfThenElse(eq,
                                new PirTerm.Const(Constant.bool(true)), recurse);
                    }));
        }

        // MapLib.insert(map, key, value) — wrap key/value, MkPairData + MkCons
        if (isMapLibClass(className) && methodName.equals("insert") && args.size() == 3) {
            var key = PirHelpers.wrapEncode(args.get(1), safeArgType(argTypes, 1));
            var value = PirHelpers.wrapEncode(args.get(2), safeArgType(argTypes, 2));
            var pair = builtinApp2(DefaultFun.MkPairData, key, value);
            return Optional.of(builtinApp2(DefaultFun.MkCons, pair, args.get(0)));
        }

        // MapLib.member(map, key) — wrap key, pairListSearch returning bool
        if (isMapLibClass(className) && methodName.equals("member") && args.size() == 2) {
            var key = PirHelpers.wrapEncode(args.get(1), safeArgType(argTypes, 1));
            return Optional.of(PirHelpers.pairListSearch("mb", args.get(0), key,
                    new PirTerm.Const(Constant.bool(false)),
                    (h, k, goTail) -> {
                        var fstH = builtinApp1(DefaultFun.FstPair, h);
                        var eq = builtinApp2(DefaultFun.EqualsData, fstH, k);
                        return new PirTerm.IfThenElse(eq,
                                new PirTerm.Const(Constant.bool(true)), goTail);
                    }));
        }

        // MapLib.lookup(map, key) — wrap key, pairListSearch returning Optional
        if (isMapLibClass(className) && methodName.equals("lookup") && args.size() == 2) {
            var key = PirHelpers.wrapEncode(args.get(1), safeArgType(argTypes, 1));
            return Optional.of(PirHelpers.pairListSearch("lk", args.get(0), key,
                    PirHelpers.mkNone(),
                    (h, k, goTail) -> {
                        var fstH = builtinApp1(DefaultFun.FstPair, h);
                        var sndH = builtinApp1(DefaultFun.SndPair, h);
                        var eq = builtinApp2(DefaultFun.EqualsData, fstH, k);
                        return new PirTerm.IfThenElse(eq,
                                PirHelpers.mkSome(sndH), goTail);
                    }));
        }

        // MapLib.delete(map, key) — wrap key, pairListSearch filtering out matching pair
        if (isMapLibClass(className) && methodName.equals("delete") && args.size() == 2) {
            var key = PirHelpers.wrapEncode(args.get(1), safeArgType(argTypes, 1));
            return Optional.of(PirHelpers.pairListSearch("del", args.get(0), key,
                    PirHelpers.mkNilPairData(),
                    (h, k, goTail) -> {
                        var fstH = builtinApp1(DefaultFun.FstPair, h);
                        var eq = builtinApp2(DefaultFun.EqualsData, fstH, k);
                        return new PirTerm.IfThenElse(eq, goTail,
                                builtinApp2(DefaultFun.MkCons, h, goTail));
                    }));
        }

        return lookup(className, methodName, args);
    }

    private static boolean isOptionalClass(String className) {
        return className.equals("Optional") || className.equals("java.util.Optional");
    }

    private static boolean isListsLibClass(String className) {
        return className.equals("ListsLib") || className.equals(LIB + "ListsLib");
    }

    private static boolean isMapLibClass(String className) {
        return className.equals("MapLib") || className.equals(LIB + "MapLib");
    }

    private static PirType safeArgType(List<PirType> argTypes, int index) {
        return (argTypes != null && argTypes.size() > index) ? argTypes.get(index) : new PirType.DataType();
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
     * Returns the set of method names registered for a given class.
     * Matches by simple name (e.g., "ListsLib") or FQCN against registry keys.
     *
     * @param className the simple class name or FQCN
     * @return set of method names, or empty set if class has no registered methods
     */
    public java.util.Set<String> methodsForClass(String className) {
        var methods = new java.util.TreeSet<String>();
        // Direct prefix match (works for FQCN keys)
        String directPrefix = className + ".";
        // Simple name match: look for ".ClassName." within FQCN keys
        String simplePrefix = "." + className + ".";
        for (var key : registry.keySet()) {
            if (key.startsWith(directPrefix)) {
                methods.add(key.substring(directPrefix.length()));
            } else if (key.contains(simplePrefix)) {
                int idx = key.indexOf(simplePrefix) + simplePrefix.length();
                methods.add(key.substring(idx));
            }
        }
        return methods;
    }

    /**
     * Returns the set of all registered class FQCNs.
     * Used to populate ImportResolver's knownFqcns so that imports of stdlib classes
     * (e.g., {@code import com.bloxbean.cardano.julc.stdlib.Builtins;}) resolve correctly.
     */
    public java.util.Set<String> allRegisteredClassNames() {
        var classNames = new java.util.LinkedHashSet<String>();
        for (var key : registry.keySet()) {
            int dot = key.lastIndexOf('.');
            if (dot > 0) {
                classNames.add(key.substring(0, dot));
            }
        }
        return classNames;
    }

    /**
     * Returns all stdlib class FQCNs that should be recognized by ImportResolver.
     * These are the class names that can be imported in validator source code.
     */
    public static java.util.Set<String> stdlibClassFqcns() {
        var fqcns = new java.util.LinkedHashSet<String>();
        fqcns.add(PKG + "Builtins");
        for (String lib : List.of("ContextsLib", "ListsLib", "MapLib", "ValuesLib",
                "OutputLib", "MathLib", "IntervalLib", "CryptoLib",
                "ByteStringLib", "BitwiseLib", "AddressLib", "BlsLib", "NativeValueLib")) {
            fqcns.add(LIB + lib);
        }
        return fqcns;
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
        registerOptionalFactories(reg);
        registerValueFactories(reg);
        registerIntervalFactories(reg);
        return reg;
    }

    // ---- Declarative builtin tables ----
    // Each entry: {methodName, DefaultFun, arity}. Registered as standard builtinApp1/2/3 wrappers.

    /** 1-arg builtin operations: methodName → Builtin(fun, arg0) */
    private static final Object[][] BUILTIN_1_ARG = {
            // List operations
            {"headList",            DefaultFun.HeadList},
            {"tailList",            DefaultFun.TailList},
            {"nullList",            DefaultFun.NullList},
            {"fstPair",             DefaultFun.FstPair},
            {"sndPair",             DefaultFun.SndPair},
            // Data encode/decode
            {"iData",               DefaultFun.IData},
            {"bData",               DefaultFun.BData},
            {"listData",            DefaultFun.ListData},
            {"mapData",             DefaultFun.MapData},
            {"unConstrData",        DefaultFun.UnConstrData},
            {"unIData",             DefaultFun.UnIData},
            {"unBData",             DefaultFun.UnBData},
            {"unListData",          DefaultFun.UnListData},
            {"unMapData",           DefaultFun.UnMapData},
            // Type-friendly aliases
            {"asBytes",             DefaultFun.UnBData},
            {"asInteger",           DefaultFun.UnIData},
            {"asList",              DefaultFun.UnListData},
            {"asMap",               DefaultFun.UnMapData},
            // ByteString operations
            {"lengthOfByteString",  DefaultFun.LengthOfByteString},
            {"encodeUtf8",          DefaultFun.EncodeUtf8},
            {"decodeUtf8",          DefaultFun.DecodeUtf8},
            {"serialiseData",       DefaultFun.SerialiseData},
            // Crypto operations
            {"sha2_256",            DefaultFun.Sha2_256},
            {"blake2b_256",         DefaultFun.Blake2b_256},
            {"sha3_256",            DefaultFun.Sha3_256},
            {"blake2b_224",         DefaultFun.Blake2b_224},
            {"keccak_256",          DefaultFun.Keccak_256},
            {"ripemd_160",          DefaultFun.Ripemd_160},
            // Bitwise operations
            {"complementByteString", DefaultFun.ComplementByteString},
            {"countSetBits",        DefaultFun.CountSetBits},
            {"findFirstSetBit",     DefaultFun.FindFirstSetBit},
            // BLS12-381 G1 operations (1-arg)
            {"bls12_381_G1_neg",              DefaultFun.Bls12_381_G1_neg},
            {"bls12_381_G1_compress",         DefaultFun.Bls12_381_G1_compress},
            {"bls12_381_G1_uncompress",       DefaultFun.Bls12_381_G1_uncompress},
            // BLS12-381 G2 operations (1-arg)
            {"bls12_381_G2_neg",              DefaultFun.Bls12_381_G2_neg},
            {"bls12_381_G2_compress",         DefaultFun.Bls12_381_G2_compress},
            {"bls12_381_G2_uncompress",       DefaultFun.Bls12_381_G2_uncompress},
            // PV11 Array operations (1-arg)
            {"listToArray",                   DefaultFun.ListToArray},
            {"lengthOfArray",                 DefaultFun.LengthOfArray},
            // PV11 MaryEraValue operations (1-arg)
            {"valueData",                     DefaultFun.ValueData},
            {"unValueData",                   DefaultFun.UnValueData},
    };

    /** 2-arg builtin operations: methodName → Builtin(fun, arg0, arg1) */
    private static final Object[][] BUILTIN_2_ARG = {
            {"mkCons",              DefaultFun.MkCons},
            {"mkPairData",          DefaultFun.MkPairData},
            {"constrData",          DefaultFun.ConstrData},
            {"equalsData",          DefaultFun.EqualsData},
            // ByteString operations
            {"indexByteString",     DefaultFun.IndexByteString},
            {"consByteString",      DefaultFun.ConsByteString},
            {"appendByteString",    DefaultFun.AppendByteString},
            {"equalsByteString",    DefaultFun.EqualsByteString},
            {"lessThanByteString",  DefaultFun.LessThanByteString},
            {"lessThanEqualsByteString", DefaultFun.LessThanEqualsByteString},
            {"byteStringToInteger", DefaultFun.ByteStringToInteger},
            {"replicateByte",       DefaultFun.ReplicateByte},
            // Bitwise operations
            {"readBit",             DefaultFun.ReadBit},
            {"shiftByteString",     DefaultFun.ShiftByteString},
            {"rotateByteString",    DefaultFun.RotateByteString},
            // BLS12-381 G1 operations (2-arg)
            {"bls12_381_G1_add",              DefaultFun.Bls12_381_G1_add},
            {"bls12_381_G1_scalarMul",        DefaultFun.Bls12_381_G1_scalarMul},
            {"bls12_381_G1_equal",            DefaultFun.Bls12_381_G1_equal},
            {"bls12_381_G1_hashToGroup",      DefaultFun.Bls12_381_G1_hashToGroup},
            // BLS12-381 G2 operations (2-arg)
            {"bls12_381_G2_add",              DefaultFun.Bls12_381_G2_add},
            {"bls12_381_G2_scalarMul",        DefaultFun.Bls12_381_G2_scalarMul},
            {"bls12_381_G2_equal",            DefaultFun.Bls12_381_G2_equal},
            {"bls12_381_G2_hashToGroup",      DefaultFun.Bls12_381_G2_hashToGroup},
            // BLS12-381 Pairing operations (2-arg)
            {"bls12_381_millerLoop",          DefaultFun.Bls12_381_millerLoop},
            {"bls12_381_mulMlResult",         DefaultFun.Bls12_381_mulMlResult},
            {"bls12_381_finalVerify",         DefaultFun.Bls12_381_finalVerify},
            // BLS12-381 Multi-Scalar Multiplication (2-arg: scalars list, points list)
            {"bls12_381_G1_multiScalarMul",   DefaultFun.Bls12_381_G1_multiScalarMul},
            {"bls12_381_G2_multiScalarMul",   DefaultFun.Bls12_381_G2_multiScalarMul},
            // PV11 List extensions (2-arg)
            {"dropList",                      DefaultFun.DropList},
            // PV11 Array operations (2-arg)
            {"indexArray",                    DefaultFun.IndexArray},
            {"multiIndexArray",               DefaultFun.MultiIndexArray},
            // PV11 MaryEraValue operations (2-arg)
            {"unionValue",                    DefaultFun.UnionValue},
            {"valueContains",                 DefaultFun.ValueContains},
            {"scaleValue",                    DefaultFun.ScaleValue},
    };

    /** 3-arg builtin operations: methodName → Builtin(fun, arg0, arg1, arg2) */
    private static final Object[][] BUILTIN_3_ARG = {
            {"sliceByteString",     DefaultFun.SliceByteString},
            {"integerToByteString", DefaultFun.IntegerToByteString},
            // Crypto operations
            {"verifyEd25519Signature",          DefaultFun.VerifyEd25519Signature},
            {"verifyEcdsaSecp256k1Signature",   DefaultFun.VerifyEcdsaSecp256k1Signature},
            {"verifySchnorrSecp256k1Signature", DefaultFun.VerifySchnorrSecp256k1Signature},
            // Bitwise operations
            {"andByteString",       DefaultFun.AndByteString},
            {"orByteString",        DefaultFun.OrByteString},
            {"xorByteString",       DefaultFun.XorByteString},
            {"writeBits",           DefaultFun.WriteBits},
            // Math operations
            {"expModInteger",       DefaultFun.ExpModInteger},
            // PV11 MaryEraValue operations (3-arg)
            {"lookupCoin",          DefaultFun.LookupCoin},
    };

    /**
     * Register raw UPLC builtin operations under the "Builtins" class name.
     * These are the bridge between Java stdlib sources and the UPLC machine.
     */
    private static void registerBuiltins(StdlibRegistry reg) {
        String B = PKG + "Builtins";

        // Table-driven registration for standard builtin wrappers
        for (var entry : BUILTIN_1_ARG) {
            var method = (String) entry[0];
            var fun = (DefaultFun) entry[1];
            reg.register(B, method, args -> {
                requireArgs("Builtins." + method, args, 1);
                return builtinApp1(fun, args.get(0));
            });
        }
        for (var entry : BUILTIN_2_ARG) {
            var method = (String) entry[0];
            var fun = (DefaultFun) entry[1];
            reg.register(B, method, args -> {
                requireArgs("Builtins." + method, args, 2);
                return builtinApp2(fun, args.get(0), args.get(1));
            });
        }
        for (var entry : BUILTIN_3_ARG) {
            var method = (String) entry[0];
            var fun = (DefaultFun) entry[1];
            reg.register(B, method, args -> {
                requireArgs("Builtins." + method, args, 3);
                return builtinApp3(fun, args.get(0), args.get(1), args.get(2));
            });
        }

        // Special cases that don't follow the standard builtin pattern

        // 0-arg: mkNilData/mkNilPairData need unit argument
        reg.register(B, "mkNilData", args -> {
            requireArgs("Builtins.mkNilData", args, 0);
            return builtinApp1(DefaultFun.MkNilData, new PirTerm.Const(Constant.unit()));
        });
        reg.register(B, "mkNilPairData", args -> {
            requireArgs("Builtins.mkNilPairData", args, 0);
            return builtinApp1(DefaultFun.MkNilPairData, new PirTerm.Const(Constant.unit()));
        });

        // error: returns Error term (not a builtin application)
        reg.register(B, "error", args -> {
            requireArgs("Builtins.error", args, 0);
            return new PirTerm.Error(new PirType.DataType());
        });

        // trace: returns Trace term (not a builtin application)
        reg.register(B, "trace", args -> {
            requireArgs("Builtins.trace", args, 2);
            return new PirTerm.Trace(args.get(0), args.get(1));
        });

        // constrTag/constrFields: compound operations (UnConstrData + FstPair/SndPair)
        reg.register(B, "constrTag", args -> {
            requireArgs("Builtins.constrTag", args, 1);
            return builtinApp1(DefaultFun.FstPair,
                    builtinApp1(DefaultFun.UnConstrData, args.get(0)));
        });
        reg.register(B, "constrFields", args -> {
            requireArgs("Builtins.constrFields", args, 1);
            return builtinApp1(DefaultFun.SndPair,
                    builtinApp1(DefaultFun.UnConstrData, args.get(0)));
        });

        // emptyByteString: constant (not a builtin application)
        reg.register(B, "emptyByteString", args -> {
            requireArgs("Builtins.emptyByteString", args, 0);
            return new PirTerm.Const(Constant.byteString(new byte[0]));
        });

        // toByteString: identity on-chain (value is already a ByteString)
        reg.register(B, "toByteString", args -> {
            requireArgs("Builtins.toByteString", args, 1);
            return args.get(0);
        });

        // PV11 InsertCoin: 4-arg builtin (special case, not in tables)
        reg.register(B, "insertCoin", args -> {
            requireArgs("Builtins.insertCoin", args, 4);
            return builtinApp4(DefaultFun.InsertCoin, args.get(0), args.get(1), args.get(2), args.get(3));
        });
    }

    /**
     * Register ListsLib HOF methods that require lambda/LetRec support.
     * These cannot be compiled from @OnchainLibrary Java source.
     * Non-HOF methods are compiled from Java source in onchain/ListsLib.java.
     */
    private static void registerListsLibHof(StdlibRegistry reg) {
        String L = LIB + "ListsLib";
        reg.register(L, "any", args -> {
            requireArgs("ListsLib.any", args, 2);
            return ListsLibHof.any(args.get(0), args.get(1));
        });

        reg.register(L, "all", args -> {
            requireArgs("ListsLib.all", args, 2);
            return ListsLibHof.all(args.get(0), args.get(1));
        });

        reg.register(L, "find", args -> {
            requireArgs("ListsLib.find", args, 2);
            return ListsLibHof.find(args.get(0), args.get(1));
        });

        reg.register(L, "foldl", args -> {
            requireArgs("ListsLib.foldl", args, 3);
            return ListsLibHof.foldl(args.get(0), args.get(1), args.get(2));
        });

        reg.register(L, "map", args -> {
            requireArgs("ListsLib.map", args, 2);
            return ListsLibHof.map(args.get(0), args.get(1));
        });

        reg.register(L, "filter", args -> {
            requireArgs("ListsLib.filter", args, 2);
            return ListsLibHof.filter(args.get(0), args.get(1));
        });

        reg.register(L, "zip", args -> {
            requireArgs("ListsLib.zip", args, 2);
            return ListsLibHof.zip(args.get(0), args.get(1));
        });
    }

    /**
     * Register ContextsLib.trace as inline PIR.
     * This method uses UPLC Text type and cannot be compiled from @OnchainLibrary Java source.
     */
    private static void registerContextsTrace(StdlibRegistry reg) {
        reg.register(LIB + "ContextsLib", "trace", args -> {
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

        // JulcMap.empty() → MkNilPairData(unit)
        reg.register("JulcMap", "empty", args -> {
            requireArgs("JulcMap.empty", args, 0);
            return builtinApp1(DefaultFun.MkNilPairData, new PirTerm.Const(Constant.unit()));
        });

        // JulcArray.fromList(list) → ListToArray(list) — PV11 only
        reg.register("JulcArray", "fromList", args -> {
            requireArgs("JulcArray.fromList", args, 1);
            return builtinApp1(DefaultFun.ListToArray, args.get(0));
        });
    }

    /**
     * Register .of() factory methods for ledger hash types.
     * On-chain, these are identity — the byte[] is already the underlying data.
     */
    private static void registerLedgerTypeFactories(StdlibRegistry reg) {
        String ledgerPkg = "com.bloxbean.cardano.julc.ledger.";
        for (String type : List.of("PubKeyHash", "ScriptHash", "ValidatorHash",
                                    "PolicyId", "TokenName", "DatumHash", "TxId")) {
            reg.register(ledgerPkg + type, "of", args -> {
                requireArgs(type + ".of", args, 1);
                return args.get(0);
            });
        }
    }

    /**
     * Register Optional.of() and Optional.empty() factory methods.
     * On-chain, Optional follows Plutus Maybe convention:
     * Some(x) = ConstrData(0, [x]), None = ConstrData(1, []).
     *
     * Optional.of(x) is handled specially in the typed lookup override above
     * to get correct field type encoding. The registration here is a fallback
     * for the untyped lookup path (treats arg as already Data).
     */
    private static void registerOptionalFactories(StdlibRegistry reg) {
        // Register under both simple and FQCN for import flexibility
        for (String cls : List.of("Optional", "java.util.Optional")) {
            // Optional.of(x) — fallback for untyped lookup (arg assumed to be Data)
            reg.register(cls, "of", args -> {
                requireArgs("Optional.of", args, 1);
                var recordType = new PirType.RecordType("Optional",
                        List.of(new PirType.Field("value", new PirType.DataType())));
                return new PirTerm.DataConstr(0, recordType, List.of(args.get(0)));
            });

            // Optional.empty() → ConstrData(1, [])
            reg.register(cls, "empty", args -> {
                requireArgs("Optional.empty", args, 0);
                var recordType = new PirType.RecordType("Optional", List.of());
                return new PirTerm.DataConstr(1, recordType, List.of());
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

    private static PirTerm builtinApp4(DefaultFun fun, PirTerm a, PirTerm b, PirTerm c, PirTerm d) {
        return new PirTerm.App(
                new PirTerm.App(
                        new PirTerm.App(
                                new PirTerm.App(new PirTerm.Builtin(fun), a), b), c), d);
    }

    // ---- Value factory methods ----

    private static final String LEDGER_PKG = "com.bloxbean.cardano.julc.ledger.";

    /**
     * Register Value.zero(), Value.lovelace(amt), Value.singleton(policy, token, qty).
     * On-chain, Value is MapType (pair list). These produce raw pair lists;
     * DataConstr field encoding applies MapData when stored in records.
     */
    private static void registerValueFactories(StdlibRegistry reg) {
        String V = LEDGER_PKG + "Value";

        // Value.zero() → empty pair list, wrapped as MapData for Data encoding
        reg.register(V, "zero", args -> {
            requireArgs("Value.zero", args, 0);
            return builtinApp1(DefaultFun.MapData,
                    builtinApp1(DefaultFun.MkNilPairData, new PirTerm.Const(Constant.unit())));
        });

        // Value.lovelace(amount) → {emptyBS → {emptyBS → amount}}
        reg.register(V, "lovelace", args -> {
            requireArgs("Value.lovelace", args, 1);
            var amt = args.get(0);
            var unit = new PirTerm.Const(Constant.unit());
            var emptyBS = new PirTerm.Const(Constant.byteString(new byte[0]));
            var emptyPairList = builtinApp1(DefaultFun.MkNilPairData, unit);
            // Inner map: {emptyBS → IData(amt)}
            var innerPair = builtinApp2(DefaultFun.MkPairData,
                    builtinApp1(DefaultFun.BData, emptyBS),
                    builtinApp1(DefaultFun.IData, amt));
            var innerList = builtinApp2(DefaultFun.MkCons, innerPair, emptyPairList);
            // Outer map: {emptyBS → MapData(innerList)}
            var outerPair = builtinApp2(DefaultFun.MkPairData,
                    builtinApp1(DefaultFun.BData, emptyBS),
                    builtinApp1(DefaultFun.MapData, innerList));
            return builtinApp1(DefaultFun.MapData,
                    builtinApp2(DefaultFun.MkCons, outerPair,
                            builtinApp1(DefaultFun.MkNilPairData, unit)));
        });

        // Value.singleton(policy, token, qty) → {policy → {token → qty}}
        reg.register(V, "singleton", args -> {
            requireArgs("Value.singleton", args, 3);
            var policy = args.get(0);
            var token = args.get(1);
            var qty = args.get(2);
            var unit = new PirTerm.Const(Constant.unit());
            var emptyPairList = builtinApp1(DefaultFun.MkNilPairData, unit);
            // Inner map: {BData(token) → IData(qty)}
            var innerPair = builtinApp2(DefaultFun.MkPairData,
                    builtinApp1(DefaultFun.BData, token),
                    builtinApp1(DefaultFun.IData, qty));
            var innerList = builtinApp2(DefaultFun.MkCons, innerPair, emptyPairList);
            // Outer map: {BData(policy) → MapData(innerList)}
            var outerPair = builtinApp2(DefaultFun.MkPairData,
                    builtinApp1(DefaultFun.BData, policy),
                    builtinApp1(DefaultFun.MapData, innerList));
            return builtinApp1(DefaultFun.MapData,
                    builtinApp2(DefaultFun.MkCons, outerPair,
                            builtinApp1(DefaultFun.MkNilPairData, unit)));
        });
    }

    // ---- Interval factory methods ----

    /**
     * Register Interval.always(), never(), after(t), before(t), between(a,b).
     * Interval on-chain: Constr(0, [lower_bound, upper_bound]).
     * IntervalBound: Constr(0, [bound_type, inclusive_bool]).
     * IntervalBoundType: NegInf→Constr(0,[]), Finite(t)→Constr(1,[IData(t)]), PosInf→Constr(2,[]).
     */
    private static void registerIntervalFactories(StdlibRegistry reg) {
        String I = LEDGER_PKG + "Interval";

        // Interval.always() → Interval(IB(NegInf, True), IB(PosInf, True))
        reg.register(I, "always", args -> {
            requireArgs("Interval.always", args, 0);
            return intervalConstr(
                    intervalBound(negInf(), boolTrue()),
                    intervalBound(posInf(), boolTrue()));
        });

        // Interval.never() → Interval(IB(PosInf, True), IB(NegInf, True))
        reg.register(I, "never", args -> {
            requireArgs("Interval.never", args, 0);
            return intervalConstr(
                    intervalBound(posInf(), boolTrue()),
                    intervalBound(negInf(), boolTrue()));
        });

        // Interval.after(time) → Interval(IB(Finite(time), True), IB(PosInf, True))
        reg.register(I, "after", args -> {
            requireArgs("Interval.after", args, 1);
            return intervalConstr(
                    intervalBound(finite(args.get(0)), boolTrue()),
                    intervalBound(posInf(), boolTrue()));
        });

        // Interval.before(time) → Interval(IB(NegInf, True), IB(Finite(time), True))
        reg.register(I, "before", args -> {
            requireArgs("Interval.before", args, 1);
            return intervalConstr(
                    intervalBound(negInf(), boolTrue()),
                    intervalBound(finite(args.get(0)), boolTrue()));
        });

        // Interval.between(from, to) → Interval(IB(Finite(from), True), IB(Finite(to), True))
        reg.register(I, "between", args -> {
            requireArgs("Interval.between", args, 2);
            return intervalConstr(
                    intervalBound(finite(args.get(0)), boolTrue()),
                    intervalBound(finite(args.get(1)), boolTrue()));
        });
    }

    // -- Interval PIR construction helpers --

    private static PirTerm boolTrue() {
        return new PirTerm.DataConstr(1, new PirType.RecordType("True", List.of()), List.of());
    }

    private static PirTerm negInf() {
        return new PirTerm.DataConstr(0, new PirType.RecordType("NegInf", List.of()), List.of());
    }

    private static PirTerm posInf() {
        return new PirTerm.DataConstr(2, new PirType.RecordType("PosInf", List.of()), List.of());
    }

    /** Finite(time) → Constr(1, [IData(time)]). Field is IntegerType so UplcGenerator wraps with IData. */
    private static PirTerm finite(PirTerm time) {
        var type = new PirType.RecordType("Finite",
                List.of(new PirType.Field("time", new PirType.IntegerType())));
        return new PirTerm.DataConstr(1, type, List.of(time));
    }

    /** IntervalBound(boundType, inclusive) → Constr(0, [boundType, inclusive]).
     *  Both fields are DataType (already ConstrData-encoded). */
    private static PirTerm intervalBound(PirTerm boundType, PirTerm inclusive) {
        var type = new PirType.RecordType("IntervalBound", List.of(
                new PirType.Field("boundType", new PirType.DataType()),
                new PirType.Field("isInclusive", new PirType.DataType())));
        return new PirTerm.DataConstr(0, type, List.of(boundType, inclusive));
    }

    /** Interval(lower, upper) → Constr(0, [lower, upper]).
     *  Both fields are DataType (already ConstrData-encoded IntervalBounds). */
    private static PirTerm intervalConstr(PirTerm lower, PirTerm upper) {
        var type = new PirType.RecordType("Interval", List.of(
                new PirType.Field("from", new PirType.DataType()),
                new PirType.Field("to", new PirType.DataType())));
        return new PirTerm.DataConstr(0, type, List.of(lower, upper));
    }
}
