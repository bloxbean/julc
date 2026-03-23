package com.bloxbean.cardano.julc.jrl.check;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry of built-in JRL functions and their type signatures.
 * <p>
 * Used by the type checker (to validate calls) and the transpiler (to generate Java code).
 */
public final class BuiltinFunctionRegistry {

    private BuiltinFunctionRegistry() {}

    /**
     * A built-in function signature.
     *
     * @param argTypes   expected argument type categories
     * @param returnType result type category
     * @param javaCall   Java code template (use %s for arguments)
     */
    public record FunctionSig(String[] argTypes, String returnType, String javaCall) {}

    private static final Map<String, FunctionSig> FUNCTIONS = Map.of(
            "sha2_256",     new FunctionSig(new String[]{"ByteString"}, "ByteString", "Builtins.sha2_256(%s)"),
            "blake2b_256",  new FunctionSig(new String[]{"ByteString"}, "ByteString", "Builtins.blake2b_256(%s)"),
            "sha3_256",     new FunctionSig(new String[]{"ByteString"}, "ByteString", "Builtins.sha3_256(%s)"),
            "length",       new FunctionSig(new String[]{"List"}, "Integer", "ListsLib.length(%s)")
    );

    /** Known JRL primitive/alias type names. */
    public static final Set<String> KNOWN_TYPES = Set.of(
            "Integer", "Lovelace", "POSIXTime",
            "ByteString", "PubKeyHash", "PolicyId", "TokenName",
            "ScriptHash", "DatumHash", "TxId",
            "Address", "Text", "Boolean"
    );

    /** Type names that map to Integer (numeric aliases). */
    public static final Set<String> NUMERIC_TYPES = Set.of(
            "Integer", "Lovelace", "POSIXTime"
    );

    /** Type names that map to ByteString / byte[]. */
    public static final Set<String> BYTES_TYPES = Set.of(
            "ByteString", "PubKeyHash", "PolicyId", "TokenName",
            "ScriptHash", "DatumHash", "TxId"
    );

    public static Optional<FunctionSig> lookup(String name) {
        return Optional.ofNullable(FUNCTIONS.get(name));
    }

    public static boolean isKnownFunction(String name) {
        return FUNCTIONS.containsKey(name);
    }

    public static boolean isKnownType(String name) {
        return KNOWN_TYPES.contains(name);
    }

    public static boolean isNumericType(String typeName) {
        return NUMERIC_TYPES.contains(typeName);
    }

    public static boolean isBytesType(String typeName) {
        return BYTES_TYPES.contains(typeName);
    }
}
