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
    }

    private static void requireArgs(String method, List<PirTerm> args, int expected) {
        if (args.size() != expected) {
            throw new IllegalArgumentException(
                    method + " expects " + expected + " arguments, got " + args.size());
        }
    }
}
