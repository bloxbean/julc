package com.bloxbean.cardano.plutus.stdlib;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;

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
public final class StdlibRegistry {

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
        registerListsLib(reg);
        registerValuesLib(reg);
        registerCryptoLib(reg);
        registerContextsLib(reg);
        registerIntervalLib(reg);
        registerByteStringLib(reg);
        registerMapLib(reg);
        registerMathLib(reg);
        registerBitwiseLib(reg);
        registerJavaMathDelegates(reg);
        return reg;
    }

    private static void registerListsLib(StdlibRegistry reg) {
        reg.register("ListsLib", "any", args -> {
            requireArgs("ListsLib.any", args, 2);
            return ListsLib.any(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "all", args -> {
            requireArgs("ListsLib.all", args, 2);
            return ListsLib.all(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "find", args -> {
            requireArgs("ListsLib.find", args, 2);
            return ListsLib.find(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "foldl", args -> {
            requireArgs("ListsLib.foldl", args, 3);
            return ListsLib.foldl(args.get(0), args.get(1), args.get(2));
        });

        reg.register("ListsLib", "length", args -> {
            requireArgs("ListsLib.length", args, 1);
            return ListsLib.length(args.get(0));
        });

        reg.register("ListsLib", "isEmpty", args -> {
            requireArgs("ListsLib.isEmpty", args, 1);
            return ListsLib.isEmpty(args.get(0));
        });

        reg.register("ListsLib", "head", args -> {
            requireArgs("ListsLib.head", args, 1);
            return ListsLib.head(args.get(0));
        });

        reg.register("ListsLib", "tail", args -> {
            requireArgs("ListsLib.tail", args, 1);
            return ListsLib.tail(args.get(0));
        });

        reg.register("ListsLib", "reverse", args -> {
            requireArgs("ListsLib.reverse", args, 1);
            return ListsLib.reverse(args.get(0));
        });

        reg.register("ListsLib", "map", args -> {
            requireArgs("ListsLib.map", args, 2);
            return ListsLib.map(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "filter", args -> {
            requireArgs("ListsLib.filter", args, 2);
            return ListsLib.filter(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "concat", args -> {
            requireArgs("ListsLib.concat", args, 2);
            return ListsLib.concat(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "nth", args -> {
            requireArgs("ListsLib.nth", args, 2);
            return ListsLib.nth(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "take", args -> {
            requireArgs("ListsLib.take", args, 2);
            return ListsLib.take(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "drop", args -> {
            requireArgs("ListsLib.drop", args, 2);
            return ListsLib.drop(args.get(0), args.get(1));
        });

        reg.register("ListsLib", "zip", args -> {
            requireArgs("ListsLib.zip", args, 2);
            return ListsLib.zip(args.get(0), args.get(1));
        });
    }

    private static void registerValuesLib(StdlibRegistry reg) {
        reg.register("ValuesLib", "lovelaceOf", args -> {
            requireArgs("ValuesLib.lovelaceOf", args, 1);
            return ValuesLib.lovelaceOf(args.get(0));
        });

        reg.register("ValuesLib", "geq", args -> {
            requireArgs("ValuesLib.geq", args, 2);
            return ValuesLib.geq(args.get(0), args.get(1));
        });

        reg.register("ValuesLib", "assetOf", args -> {
            requireArgs("ValuesLib.assetOf", args, 3);
            return ValuesLib.assetOf(args.get(0), args.get(1), args.get(2));
        });

        reg.register("ValuesLib", "geqMultiAsset", args -> {
            requireArgs("ValuesLib.geqMultiAsset", args, 2);
            return ValuesLib.geqMultiAsset(args.get(0), args.get(1));
        });

        reg.register("ValuesLib", "leq", args -> {
            requireArgs("ValuesLib.leq", args, 2);
            return ValuesLib.leq(args.get(0), args.get(1));
        });

        reg.register("ValuesLib", "eq", args -> {
            requireArgs("ValuesLib.eq", args, 2);
            return ValuesLib.eq(args.get(0), args.get(1));
        });

        reg.register("ValuesLib", "isZero", args -> {
            requireArgs("ValuesLib.isZero", args, 1);
            return ValuesLib.isZero(args.get(0));
        });

        reg.register("ValuesLib", "singleton", args -> {
            requireArgs("ValuesLib.singleton", args, 3);
            return ValuesLib.singleton(args.get(0), args.get(1), args.get(2));
        });

        reg.register("ValuesLib", "negate", args -> {
            requireArgs("ValuesLib.negate", args, 1);
            return ValuesLib.negate(args.get(0));
        });

        reg.register("ValuesLib", "flatten", args -> {
            requireArgs("ValuesLib.flatten", args, 1);
            return ValuesLib.flatten(args.get(0));
        });

        reg.register("ValuesLib", "add", args -> {
            requireArgs("ValuesLib.add", args, 2);
            return ValuesLib.add(args.get(0), args.get(1));
        });

        reg.register("ValuesLib", "subtract", args -> {
            requireArgs("ValuesLib.subtract", args, 2);
            return ValuesLib.subtract(args.get(0), args.get(1));
        });
    }

    private static void registerCryptoLib(StdlibRegistry reg) {
        reg.register("CryptoLib", "sha2_256", args -> {
            requireArgs("CryptoLib.sha2_256", args, 1);
            return CryptoLib.sha2_256(args.get(0));
        });

        reg.register("CryptoLib", "blake2b_256", args -> {
            requireArgs("CryptoLib.blake2b_256", args, 1);
            return CryptoLib.blake2b_256(args.get(0));
        });

        reg.register("CryptoLib", "verifyEd25519Signature", args -> {
            requireArgs("CryptoLib.verifyEd25519Signature", args, 3);
            return CryptoLib.verifyEd25519Signature(args.get(0), args.get(1), args.get(2));
        });

        reg.register("CryptoLib", "sha3_256", args -> {
            requireArgs("CryptoLib.sha3_256", args, 1);
            return CryptoLib.sha3_256(args.get(0));
        });

        reg.register("CryptoLib", "blake2b_224", args -> {
            requireArgs("CryptoLib.blake2b_224", args, 1);
            return CryptoLib.blake2b_224(args.get(0));
        });

        reg.register("CryptoLib", "keccak_256", args -> {
            requireArgs("CryptoLib.keccak_256", args, 1);
            return CryptoLib.keccak_256(args.get(0));
        });

        reg.register("CryptoLib", "verifyEcdsaSecp256k1", args -> {
            requireArgs("CryptoLib.verifyEcdsaSecp256k1", args, 3);
            return CryptoLib.verifyEcdsaSecp256k1(args.get(0), args.get(1), args.get(2));
        });

        reg.register("CryptoLib", "verifySchnorrSecp256k1", args -> {
            requireArgs("CryptoLib.verifySchnorrSecp256k1", args, 3);
            return CryptoLib.verifySchnorrSecp256k1(args.get(0), args.get(1), args.get(2));
        });

        reg.register("CryptoLib", "ripemd_160", args -> {
            requireArgs("CryptoLib.ripemd_160", args, 1);
            return CryptoLib.ripemd_160(args.get(0));
        });
    }

    private static void registerContextsLib(StdlibRegistry reg) {
        reg.register("ContextsLib", "signedBy", args -> {
            requireArgs("ContextsLib.signedBy", args, 2);
            return ContextsLib.signedBy(args.get(0), args.get(1));
        });

        reg.register("ContextsLib", "txInfoInputs", args -> {
            requireArgs("ContextsLib.txInfoInputs", args, 1);
            return ContextsLib.txInfoInputs(args.get(0));
        });

        reg.register("ContextsLib", "txInfoOutputs", args -> {
            requireArgs("ContextsLib.txInfoOutputs", args, 1);
            return ContextsLib.txInfoOutputs(args.get(0));
        });

        reg.register("ContextsLib", "txInfoSignatories", args -> {
            requireArgs("ContextsLib.txInfoSignatories", args, 1);
            return ContextsLib.txInfoSignatories(args.get(0));
        });

        reg.register("ContextsLib", "txInfoValidRange", args -> {
            requireArgs("ContextsLib.txInfoValidRange", args, 1);
            return ContextsLib.txInfoValidRange(args.get(0));
        });

        reg.register("ContextsLib", "getTxInfo", args -> {
            requireArgs("ContextsLib.getTxInfo", args, 1);
            return ContextsLib.getTxInfo(args.get(0));
        });

        reg.register("ContextsLib", "getRedeemer", args -> {
            requireArgs("ContextsLib.getRedeemer", args, 1);
            return ContextsLib.getRedeemer(args.get(0));
        });

        reg.register("ContextsLib", "getSpendingDatum", args -> {
            requireArgs("ContextsLib.getSpendingDatum", args, 1);
            return ContextsLib.getSpendingDatum(args.get(0));
        });

        reg.register("ContextsLib", "trace", args -> {
            requireArgs("ContextsLib.trace", args, 1);
            return ContextsLib.trace(args.get(0));
        });

        reg.register("ContextsLib", "txInfoMint", args -> {
            requireArgs("ContextsLib.txInfoMint", args, 1);
            return ContextsLib.txInfoMint(args.get(0));
        });

        reg.register("ContextsLib", "txInfoFee", args -> {
            requireArgs("ContextsLib.txInfoFee", args, 1);
            return ContextsLib.txInfoFee(args.get(0));
        });

        reg.register("ContextsLib", "txInfoId", args -> {
            requireArgs("ContextsLib.txInfoId", args, 1);
            return ContextsLib.txInfoId(args.get(0));
        });

        reg.register("ContextsLib", "findOwnInput", args -> {
            requireArgs("ContextsLib.findOwnInput", args, 1);
            return ContextsLib.findOwnInput(args.get(0));
        });

        reg.register("ContextsLib", "getContinuingOutputs", args -> {
            requireArgs("ContextsLib.getContinuingOutputs", args, 1);
            return ContextsLib.getContinuingOutputs(args.get(0));
        });

        reg.register("ContextsLib", "findDatum", args -> {
            requireArgs("ContextsLib.findDatum", args, 2);
            return ContextsLib.findDatum(args.get(0), args.get(1));
        });

        reg.register("ContextsLib", "valueSpent", args -> {
            requireArgs("ContextsLib.valueSpent", args, 1);
            return ContextsLib.valueSpent(args.get(0));
        });

        reg.register("ContextsLib", "valuePaid", args -> {
            requireArgs("ContextsLib.valuePaid", args, 2);
            return ContextsLib.valuePaid(args.get(0), args.get(1));
        });

        reg.register("ContextsLib", "ownHash", args -> {
            requireArgs("ContextsLib.ownHash", args, 1);
            return ContextsLib.ownHash(args.get(0));
        });

        reg.register("ContextsLib", "scriptOutputsAt", args -> {
            requireArgs("ContextsLib.scriptOutputsAt", args, 2);
            return ContextsLib.scriptOutputsAt(args.get(0), args.get(1));
        });
    }

    private static void registerIntervalLib(StdlibRegistry reg) {
        reg.register("IntervalLib", "contains", args -> {
            requireArgs("IntervalLib.contains", args, 2);
            return IntervalLib.contains(args.get(0), args.get(1));
        });

        reg.register("IntervalLib", "always", args -> {
            requireArgs("IntervalLib.always", args, 0);
            return IntervalLib.always();
        });

        reg.register("IntervalLib", "after", args -> {
            requireArgs("IntervalLib.after", args, 1);
            return IntervalLib.after(args.get(0));
        });

        reg.register("IntervalLib", "before", args -> {
            requireArgs("IntervalLib.before", args, 1);
            return IntervalLib.before(args.get(0));
        });

        reg.register("IntervalLib", "between", args -> {
            requireArgs("IntervalLib.between", args, 2);
            return IntervalLib.between(args.get(0), args.get(1));
        });

        reg.register("IntervalLib", "never", args -> {
            requireArgs("IntervalLib.never", args, 0);
            return IntervalLib.never();
        });

        reg.register("IntervalLib", "isEmpty", args -> {
            requireArgs("IntervalLib.isEmpty", args, 1);
            return IntervalLib.isEmpty(args.get(0));
        });
    }

    private static void registerByteStringLib(StdlibRegistry reg) {
        reg.register("ByteStringLib", "at", args -> {
            requireArgs("ByteStringLib.at", args, 2);
            return ByteStringLib.at(args.get(0), args.get(1));
        });

        reg.register("ByteStringLib", "cons", args -> {
            requireArgs("ByteStringLib.cons", args, 2);
            return ByteStringLib.cons(args.get(0), args.get(1));
        });

        reg.register("ByteStringLib", "slice", args -> {
            requireArgs("ByteStringLib.slice", args, 3);
            return ByteStringLib.slice(args.get(0), args.get(1), args.get(2));
        });

        reg.register("ByteStringLib", "length", args -> {
            requireArgs("ByteStringLib.length", args, 1);
            return ByteStringLib.length(args.get(0));
        });

        reg.register("ByteStringLib", "drop", args -> {
            requireArgs("ByteStringLib.drop", args, 2);
            return ByteStringLib.drop(args.get(0), args.get(1));
        });

        reg.register("ByteStringLib", "append", args -> {
            requireArgs("ByteStringLib.append", args, 2);
            return ByteStringLib.append(args.get(0), args.get(1));
        });

        reg.register("ByteStringLib", "empty", args -> {
            requireArgs("ByteStringLib.empty", args, 0);
            return ByteStringLib.empty();
        });

        reg.register("ByteStringLib", "zeros", args -> {
            requireArgs("ByteStringLib.zeros", args, 1);
            return ByteStringLib.zeros(args.get(0));
        });

        reg.register("ByteStringLib", "equals", args -> {
            requireArgs("ByteStringLib.equals", args, 2);
            return ByteStringLib.equals(args.get(0), args.get(1));
        });

        reg.register("ByteStringLib", "take", args -> {
            requireArgs("ByteStringLib.take", args, 2);
            return ByteStringLib.take(args.get(0), args.get(1));
        });

        reg.register("ByteStringLib", "lessThan", args -> {
            requireArgs("ByteStringLib.lessThan", args, 2);
            return ByteStringLib.lessThan(args.get(0), args.get(1));
        });

        reg.register("ByteStringLib", "lessThanEquals", args -> {
            requireArgs("ByteStringLib.lessThanEquals", args, 2);
            return ByteStringLib.lessThanEquals(args.get(0), args.get(1));
        });

        reg.register("ByteStringLib", "integerToByteString", args -> {
            requireArgs("ByteStringLib.integerToByteString", args, 3);
            return ByteStringLib.integerToByteString(args.get(0), args.get(1), args.get(2));
        });

        reg.register("ByteStringLib", "byteStringToInteger", args -> {
            requireArgs("ByteStringLib.byteStringToInteger", args, 2);
            return ByteStringLib.byteStringToInteger(args.get(0), args.get(1));
        });

        reg.register("ByteStringLib", "encodeUtf8", args -> {
            requireArgs("ByteStringLib.encodeUtf8", args, 1);
            return ByteStringLib.encodeUtf8(args.get(0));
        });

        reg.register("ByteStringLib", "decodeUtf8", args -> {
            requireArgs("ByteStringLib.decodeUtf8", args, 1);
            return ByteStringLib.decodeUtf8(args.get(0));
        });

        reg.register("ByteStringLib", "serialiseData", args -> {
            requireArgs("ByteStringLib.serialiseData", args, 1);
            return ByteStringLib.serialiseData(args.get(0));
        });
    }

    private static void registerMapLib(StdlibRegistry reg) {
        reg.register("MapLib", "lookup", args -> {
            requireArgs("MapLib.lookup", args, 2);
            return MapLib.lookup(args.get(0), args.get(1));
        });

        reg.register("MapLib", "member", args -> {
            requireArgs("MapLib.member", args, 2);
            return MapLib.member(args.get(0), args.get(1));
        });

        reg.register("MapLib", "insert", args -> {
            requireArgs("MapLib.insert", args, 3);
            return MapLib.insert(args.get(0), args.get(1), args.get(2));
        });

        reg.register("MapLib", "delete", args -> {
            requireArgs("MapLib.delete", args, 2);
            return MapLib.delete(args.get(0), args.get(1));
        });

        reg.register("MapLib", "keys", args -> {
            requireArgs("MapLib.keys", args, 1);
            return MapLib.keys(args.get(0));
        });

        reg.register("MapLib", "values", args -> {
            requireArgs("MapLib.values", args, 1);
            return MapLib.values(args.get(0));
        });

        reg.register("MapLib", "toList", args -> {
            requireArgs("MapLib.toList", args, 1);
            return MapLib.toList(args.get(0));
        });

        reg.register("MapLib", "fromList", args -> {
            requireArgs("MapLib.fromList", args, 1);
            return MapLib.fromList(args.get(0));
        });

        reg.register("MapLib", "size", args -> {
            requireArgs("MapLib.size", args, 1);
            return MapLib.size(args.get(0));
        });
    }

    private static void registerMathLib(StdlibRegistry reg) {
        reg.register("MathLib", "abs", args -> {
            requireArgs("MathLib.abs", args, 1);
            return MathLib.abs(args.get(0));
        });

        reg.register("MathLib", "max", args -> {
            requireArgs("MathLib.max", args, 2);
            return MathLib.max(args.get(0), args.get(1));
        });

        reg.register("MathLib", "min", args -> {
            requireArgs("MathLib.min", args, 2);
            return MathLib.min(args.get(0), args.get(1));
        });

        reg.register("MathLib", "divMod", args -> {
            requireArgs("MathLib.divMod", args, 2);
            return MathLib.divMod(args.get(0), args.get(1));
        });

        reg.register("MathLib", "quotRem", args -> {
            requireArgs("MathLib.quotRem", args, 2);
            return MathLib.quotRem(args.get(0), args.get(1));
        });

        reg.register("MathLib", "pow", args -> {
            requireArgs("MathLib.pow", args, 2);
            return MathLib.pow(args.get(0), args.get(1));
        });

        reg.register("MathLib", "sign", args -> {
            requireArgs("MathLib.sign", args, 1);
            return MathLib.sign(args.get(0));
        });

        reg.register("MathLib", "expMod", args -> {
            requireArgs("MathLib.expMod", args, 3);
            return MathLib.expMod(args.get(0), args.get(1), args.get(2));
        });
    }

    private static void registerBitwiseLib(StdlibRegistry reg) {
        reg.register("BitwiseLib", "andByteString", args -> {
            requireArgs("BitwiseLib.andByteString", args, 3);
            return BitwiseLib.andByteString(args.get(0), args.get(1), args.get(2));
        });

        reg.register("BitwiseLib", "orByteString", args -> {
            requireArgs("BitwiseLib.orByteString", args, 3);
            return BitwiseLib.orByteString(args.get(0), args.get(1), args.get(2));
        });

        reg.register("BitwiseLib", "xorByteString", args -> {
            requireArgs("BitwiseLib.xorByteString", args, 3);
            return BitwiseLib.xorByteString(args.get(0), args.get(1), args.get(2));
        });

        reg.register("BitwiseLib", "complementByteString", args -> {
            requireArgs("BitwiseLib.complementByteString", args, 1);
            return BitwiseLib.complementByteString(args.get(0));
        });

        reg.register("BitwiseLib", "readBit", args -> {
            requireArgs("BitwiseLib.readBit", args, 2);
            return BitwiseLib.readBit(args.get(0), args.get(1));
        });

        reg.register("BitwiseLib", "writeBits", args -> {
            requireArgs("BitwiseLib.writeBits", args, 3);
            return BitwiseLib.writeBits(args.get(0), args.get(1), args.get(2));
        });

        reg.register("BitwiseLib", "shiftByteString", args -> {
            requireArgs("BitwiseLib.shiftByteString", args, 2);
            return BitwiseLib.shiftByteString(args.get(0), args.get(1));
        });

        reg.register("BitwiseLib", "rotateByteString", args -> {
            requireArgs("BitwiseLib.rotateByteString", args, 2);
            return BitwiseLib.rotateByteString(args.get(0), args.get(1));
        });

        reg.register("BitwiseLib", "countSetBits", args -> {
            requireArgs("BitwiseLib.countSetBits", args, 1);
            return BitwiseLib.countSetBits(args.get(0));
        });

        reg.register("BitwiseLib", "findFirstSetBit", args -> {
            requireArgs("BitwiseLib.findFirstSetBit", args, 1);
            return BitwiseLib.findFirstSetBit(args.get(0));
        });
    }

    /**
     * Delegates java.lang.Math static methods to MathLib equivalents.
     * Allows developers to write {@code Math.abs(x)} instead of {@code MathLib.abs(x)}.
     */
    private static void registerJavaMathDelegates(StdlibRegistry reg) {
        reg.register("Math", "abs", args -> {
            requireArgs("Math.abs", args, 1);
            return MathLib.abs(args.get(0));
        });

        reg.register("Math", "max", args -> {
            requireArgs("Math.max", args, 2);
            return MathLib.max(args.get(0), args.get(1));
        });

        reg.register("Math", "min", args -> {
            requireArgs("Math.min", args, 2);
            return MathLib.min(args.get(0), args.get(1));
        });
    }

    private static void requireArgs(String method, List<PirTerm> args, int expected) {
        if (args.size() != expected) {
            throw new IllegalArgumentException(
                    method + " expects " + expected + " arguments, got " + args.size());
        }
    }
}
