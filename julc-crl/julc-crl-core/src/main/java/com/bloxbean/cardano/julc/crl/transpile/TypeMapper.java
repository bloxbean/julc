package com.bloxbean.cardano.julc.crl.transpile;

import com.bloxbean.cardano.julc.crl.ast.TypeRef;
import com.bloxbean.cardano.julc.crl.check.BuiltinFunctionRegistry;

/**
 * Maps CRL type names to Java type strings for code generation.
 */
public final class TypeMapper {

    private TypeMapper() {}

    /**
     * Convert a CRL TypeRef to a Java type string.
     */
    public static String toJavaType(TypeRef ref) {
        return switch (ref) {
            case TypeRef.SimpleType st -> toJavaType(st.name());
            case TypeRef.ListType lt -> "List<" + toJavaTypeBoxed(lt.elementType()) + ">";
            case TypeRef.OptionalType ot -> "Optional<" + toJavaTypeBoxed(ot.elementType()) + ">";
        };
    }

    /**
     * Convert a CRL type name to a Java type string.
     */
    public static String toJavaType(String crlType) {
        return switch (crlType) {
            case "Integer", "Lovelace", "POSIXTime" -> "BigInteger";
            case "ByteString", "PubKeyHash", "PolicyId", "TokenName",
                 "ScriptHash", "DatumHash", "TxId" -> "byte[]";
            case "Address" -> "Address";
            case "Text" -> "String";
            case "Boolean" -> "boolean";
            default -> crlType; // user-declared types pass through
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
     * Whether a CRL type name is numeric (maps to BigInteger).
     */
    public static boolean isNumeric(String crlType) {
        return BuiltinFunctionRegistry.isNumericType(crlType);
    }
}
