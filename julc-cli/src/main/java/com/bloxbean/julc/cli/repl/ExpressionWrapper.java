package com.bloxbean.julc.cli.repl;

import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Wraps bare expressions into a compilable Java class for REPL evaluation.
 * <p>
 * Generates a synthetic class with auto-imports for all stdlib libraries,
 * ledger types, and core types so users can type expressions directly.
 */
public final class ExpressionWrapper {

    private ExpressionWrapper() {}

    static final String CLASS_NAME = "__Repl";
    static final String METHOD_NAME = "__eval";

    /** Known integer-returning method patterns. */
    private static final Set<String> INTEGER_METHODS = Set.of(
            "ListsLib.length", "MathLib.abs", "MathLib.max", "MathLib.min",
            "MathLib.pow", "MathLib.sign", "MathLib.expMod",
            "Builtins.unIData", "Builtins.lengthOfByteString"
    );

    /** Known boolean-returning method patterns. */
    private static final Set<String> BOOLEAN_METHODS = Set.of(
            "ListsLib.isEmpty", "ListsLib.contains", "ListsLib.containsInt",
            "ListsLib.containsBytes", "ListsLib.hasDuplicateInts", "ListsLib.hasDuplicateBytes",
            "MapLib.member", "Builtins.nullList",
            "ValuesLib.leq", "ValuesLib.eq", "ValuesLib.isZero",
            "CryptoLib.verifyEcdsaSecp256k1", "CryptoLib.verifySchnorrSecp256k1",
            "BitwiseLib.readBit", "AddressLib.isScriptAddress", "AddressLib.isPubKeyAddress"
    );

    /** Known byte[]-returning method patterns. */
    private static final Set<String> BYTES_METHODS = Set.of(
            "Builtins.sha2_256", "Builtins.sha3_256", "Builtins.blake2b_224", "Builtins.blake2b_256",
            "Builtins.bData", "Builtins.unBData",
            "ByteStringLib.take", "ByteStringLib.integerToByteString",
            "ByteStringLib.encodeUtf8", "ByteStringLib.serialiseData",
            "CryptoLib.ripemd_160", "AddressLib.credentialHash",
            "BitwiseLib.andByteString", "BitwiseLib.orByteString",
            "BitwiseLib.xorByteString", "BitwiseLib.complementByteString",
            "BitwiseLib.shiftByteString", "BitwiseLib.rotateByteString"
    );

    private static final Pattern ARITHMETIC_OPS = Pattern.compile("[+\\-*/%]");
    private static final Pattern COMPARISON_OPS = Pattern.compile("(==|!=|<=?|>=?|&&|\\|\\|)");

    /**
     * Wrap an expression in a compilable Java source with auto-imports.
     *
     * @param expression   the user's expression
     * @param userImports  additional user-added imports (from :import)
     * @return the wrapped Java source code
     */
    public static String wrap(String expression, List<String> userImports) {
        String returnType = inferReturnType(expression);
        String body = formatBody(expression, returnType);
        return buildSource(body, returnType, userImports);
    }

    /**
     * Wrap with PlutusData return type and cast (fallback when type inference fails).
     */
    public static String wrapAsData(String expression, List<String> userImports) {
        String body = "return (PlutusData)(Object)(" + expression + ");";
        return buildSource(body, "PlutusData", userImports);
    }

    /**
     * Infer the return type from the expression pattern.
     */
    static String inferReturnType(String expression) {
        String trimmed = expression.trim();

        // Boolean literals
        if (trimmed.equals("true") || trimmed.equals("false")) {
            return "boolean";
        }

        // Numeric literals (BigInteger range)
        if (trimmed.matches("-?\\d+")) {
            return "BigInteger";
        }

        // String literals
        if (trimmed.startsWith("\"")) {
            return "String";
        }

        // byte[] construction
        if (trimmed.startsWith("new byte[]")) {
            return "byte[]";
        }

        // Check for known method return types
        for (String method : INTEGER_METHODS) {
            if (trimmed.contains(method)) return "BigInteger";
        }
        for (String method : BOOLEAN_METHODS) {
            if (trimmed.contains(method)) return "boolean";
        }
        for (String method : BYTES_METHODS) {
            if (trimmed.contains(method)) return "byte[]";
        }

        // Comparison/boolean operators
        if (COMPARISON_OPS.matcher(trimmed).find()) {
            return "boolean";
        }

        // Arithmetic operators (but not inside method args)
        // Simple heuristic: if top-level has arithmetic
        if (hasTopLevelArithmetic(trimmed)) {
            return "BigInteger";
        }

        // Default: PlutusData with cast
        return "PlutusData";
    }

    private static boolean hasTopLevelArithmetic(String expr) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (depth == 0 && (c == '+' || c == '-' || c == '*' || c == '/' || c == '%')) {
                // Exclude unary minus at start
                if (c == '-' && i == 0) continue;
                return true;
            }
        }
        return false;
    }

    private static String formatBody(String expression, String returnType) {
        String trimmed = expression.trim();

        // Multi-statement block (contains semicolons not at end)
        if (trimmed.contains(";") && !trimmed.endsWith(";")) {
            // Assume it's a block with last expression as return
            return trimmed;
        }
        if (trimmed.contains(";")) {
            // Multiple statements — last one is the return value
            // Wrap as-is, user must provide explicit returns
            return trimmed;
        }

        // Single expression
        if (returnType.equals("PlutusData")) {
            return "return (PlutusData)(Object)(" + trimmed + ");";
        }
        return "return " + trimmed + ";";
    }

    private static String buildSource(String body, String returnType, List<String> userImports) {
        var sb = new StringBuilder();

        // Auto-imports: all stdlib classes
        for (String fqcn : StdlibRegistry.stdlibClassFqcns()) {
            sb.append("import ").append(fqcn).append(";\n");
        }

        // Core types
        sb.append("import com.bloxbean.cardano.julc.core.PlutusData;\n");
        sb.append("import com.bloxbean.cardano.julc.core.types.JulcList;\n");
        sb.append("import com.bloxbean.cardano.julc.core.types.JulcMap;\n");
        sb.append("import com.bloxbean.cardano.julc.core.types.Tuple2;\n");
        sb.append("import com.bloxbean.cardano.julc.core.types.Tuple3;\n");

        // Ledger types (wildcard)
        sb.append("import com.bloxbean.cardano.julc.ledger.*;\n");

        // Java standard
        sb.append("import java.math.BigInteger;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Optional;\n");

        // User imports
        for (String imp : userImports) {
            sb.append("import ").append(imp).append(";\n");
        }

        sb.append("\nclass ").append(CLASS_NAME).append(" {\n");
        sb.append("    static ").append(returnType).append(" ").append(METHOD_NAME).append("() {\n");
        sb.append("        ").append(body).append("\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }
}
