package com.bloxbean.cardano.julc.jrl.transpile;

import com.bloxbean.cardano.julc.jrl.ast.TypeRef;
import com.bloxbean.cardano.julc.jrl.check.BuiltinFunctionRegistry;

/**
 * Maps JRL type names to Java type strings for code generation.
 */
public final class TypeMapper {

    private TypeMapper() {}

    /**
     * Convert a JRL TypeRef to a Java type string.
     */
    public static String toJavaType(TypeRef ref) {
        return switch (ref) {
            case TypeRef.SimpleType st -> toJavaType(st.name());
            case TypeRef.ListType lt -> "List<" + toJavaTypeBoxed(lt.elementType()) + ">";
            case TypeRef.OptionalType ot -> "Optional<" + toJavaTypeBoxed(ot.elementType()) + ">";
        };
    }

    /**
     * Convert a JRL type name to a Java type string.
     */
    public static String toJavaType(String jrlType) {
        return switch (jrlType) {
            case "Integer", "Lovelace", "POSIXTime" -> "BigInteger";
            case "ByteString", "PubKeyHash", "PolicyId", "TokenName",
                 "ScriptHash", "DatumHash", "TxId" -> "byte[]";
            case "Address" -> "Address";
            case "Text" -> "String";
            case "Boolean" -> "boolean";
            default -> jrlType; // user-declared types pass through
        };
    }

    /**
     * Boxed version for generic type parameters (no primitives).
     */
    private static String toJavaTypeBoxed(TypeRef ref) {
        String type = toJavaType(ref);
        return "boolean".equals(type) ? "Boolean" : type;
    }

    /**
     * Whether a JRL type name is numeric (maps to BigInteger).
     */
    public static boolean isNumeric(String jrlType) {
        return BuiltinFunctionRegistry.isNumericType(jrlType);
    }
}
