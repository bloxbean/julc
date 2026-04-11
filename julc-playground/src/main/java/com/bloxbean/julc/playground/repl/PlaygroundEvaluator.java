package com.bloxbean.julc.playground.repl;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.TermExtractor;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evaluates standalone expressions in the playground.
 * Wraps expressions into synthetic classes, compiles via JulcCompiler.compileMethod(),
 * evaluates via JulcVm, and extracts/formats the result.
 */
public final class PlaygroundEvaluator {

    private static final String CLASS_NAME = "__Repl";
    private static final String METHOD_NAME = "__eval";

    private static final Set<String> INTEGER_METHODS = Set.of(
            "ListsLib.length", "MathLib.abs", "MathLib.max", "MathLib.min",
            "MathLib.pow", "MathLib.sign", "MathLib.expMod",
            "Builtins.unIData", "Builtins.lengthOfByteString"
    );
    private static final Set<String> BOOLEAN_METHODS = Set.of(
            "ListsLib.isEmpty", "ListsLib.contains", "ListsLib.containsInt",
            "MapLib.member", "Builtins.nullList",
            "ValuesLib.leq", "ValuesLib.eq", "ValuesLib.isZero"
    );
    private static final Set<String> BYTES_METHODS = Set.of(
            "Builtins.sha2_256", "Builtins.sha3_256", "Builtins.blake2b_224", "Builtins.blake2b_256",
            "ByteStringLib.take", "CryptoLib.ripemd_160", "AddressLib.credentialHash"
    );
    private static final Pattern COMPARISON_OPS = Pattern.compile("(==|!=|<=?|>=?|&&|\\|\\|)");

    private final JulcCompiler compiler;
    private final JulcVm vm;
    private final Map<String, String> libraryPool;

    public PlaygroundEvaluator() {
        this.compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        this.vm = JulcVm.create();
        this.libraryPool = LibrarySourceResolver.scanClasspathSources(
                PlaygroundEvaluator.class.getClassLoader());
    }

    public record EvalExpressionResult(
            boolean success,
            String result,
            String type,
            long budgetCpu,
            long budgetMem,
            List<String> traces,
            String error,
            String uplc
    ) {}

    public EvalExpressionResult evaluate(String expression) {
        String source = wrap(expression);

        // Resolve libraries
        List<String> resolvedLibs;
        try {
            resolvedLibs = LibrarySourceResolver.resolve(source, libraryPool);
        } catch (Exception e) {
            resolvedLibs = List.of();
        }

        // Compile
        CompileResult cr;
        try {
            cr = compiler.compileMethod(source, METHOD_NAME, resolvedLibs);
        } catch (Exception e) {
            // Fallback: try with PlutusData cast
            try {
                String fallback = wrapAsData(expression);
                resolvedLibs = LibrarySourceResolver.resolve(fallback, libraryPool);
                cr = compiler.compileMethod(fallback, METHOD_NAME, resolvedLibs);
            } catch (Exception e2) {
                return new EvalExpressionResult(false, null, null, 0, 0, List.of(),
                        "Compilation error: " + e.getMessage(), null);
            }
        }

        if (cr.hasErrors()) {
            String errors = cr.diagnostics().stream()
                    .filter(d -> d.isError())
                    .map(d -> d.message())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Compilation failed");
            return new EvalExpressionResult(false, null, null, 0, 0, List.of(), errors, null);
        }

        // Evaluate
        var evalResult = vm.evaluate(cr.program());
        String uplc = cr.uplcFormatted();

        return switch (evalResult) {
            case EvalResult.Success s -> {
                Object value = TermExtractor.extract(s.resultTerm());
                String formatted = formatValue(value);
                String type = inferDisplayType(value);
                yield new EvalExpressionResult(true, formatted, type,
                        s.consumed().cpuSteps(), s.consumed().memoryUnits(),
                        s.traces(), null, uplc);
            }
            case EvalResult.Failure f -> new EvalExpressionResult(false, null, null,
                    f.consumed().cpuSteps(), f.consumed().memoryUnits(),
                    f.traces(), f.error(), uplc);
            case EvalResult.BudgetExhausted b -> new EvalExpressionResult(false, null, null,
                    b.consumed().cpuSteps(), b.consumed().memoryUnits(),
                    b.traces(), "Budget exhausted", uplc);
        };
    }

    private String wrap(String expression) {
        String returnType = inferReturnType(expression);
        String body;
        if (returnType.equals("PlutusData")) {
            body = "return (PlutusData)(Object)(" + expression.trim() + ");";
        } else {
            body = "return " + expression.trim() + ";";
        }
        return buildSource(body, returnType);
    }

    private String wrapAsData(String expression) {
        String body = "return (PlutusData)(Object)(" + expression.trim() + ");";
        return buildSource(body, "PlutusData");
    }

    private String inferReturnType(String expression) {
        String t = expression.trim();
        if (t.equals("true") || t.equals("false")) return "boolean";
        if (t.matches("-?\\d+")) return "BigInteger";
        if (t.startsWith("\"")) return "String";
        if (t.startsWith("new byte[]")) return "byte[]";
        for (String m : INTEGER_METHODS) if (t.contains(m)) return "BigInteger";
        for (String m : BOOLEAN_METHODS) if (t.contains(m)) return "boolean";
        for (String m : BYTES_METHODS) if (t.contains(m)) return "byte[]";
        if (COMPARISON_OPS.matcher(t).find()) return "boolean";
        if (hasTopLevelArithmetic(t)) return "BigInteger";
        return "PlutusData";
    }

    private boolean hasTopLevelArithmetic(String expr) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (depth == 0 && (c == '+' || c == '-' || c == '*' || c == '/' || c == '%')) {
                if (c == '-' && i == 0) continue;
                return true;
            }
        }
        return false;
    }

    private String buildSource(String body, String returnType) {
        var sb = new StringBuilder();
        for (String fqcn : StdlibRegistry.stdlibClassFqcns()) {
            sb.append("import ").append(fqcn).append(";\n");
        }
        sb.append("import com.bloxbean.cardano.julc.core.PlutusData;\n");
        sb.append("import com.bloxbean.cardano.julc.core.types.*;\n");
        sb.append("import com.bloxbean.cardano.julc.ledger.*;\n");
        sb.append("import java.math.BigInteger;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Optional;\n");
        sb.append("\nclass ").append(CLASS_NAME).append(" {\n");
        sb.append("    static ").append(returnType).append(" ").append(METHOD_NAME).append("() {\n");
        sb.append("        ").append(body).append("\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String formatValue(Object value) {
        if (value == null) return "()";
        if (value instanceof BigInteger bi) return bi.toString();
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof String s) return "\"" + s + "\"";
        if (value instanceof byte[] bs) return "#" + bytesToHex(bs);
        if (value instanceof PlutusData pd) return formatPlutusData(pd);
        if (value instanceof Term t) return t.toString();
        return value.toString();
    }

    private String formatPlutusData(PlutusData pd) {
        return switch (pd) {
            case PlutusData.IntData i -> i.value().toString();
            case PlutusData.BytesData b -> "#" + bytesToHex(b.value());
            case PlutusData.ConstrData c -> {
                if (c.fields().isEmpty()) yield "Constr(" + c.tag() + ")";
                var fields = c.fields().stream().map(this::formatPlutusData).toList();
                yield "Constr(" + c.tag() + ", [" + String.join(", ", fields) + "])";
            }
            case PlutusData.ListData l -> {
                var items = l.items().stream().map(this::formatPlutusData).toList();
                yield "[" + String.join(", ", items) + "]";
            }
            case PlutusData.MapData m -> {
                var entries = m.entries().stream()
                        .map(e -> formatPlutusData(e.key()) + ": " + formatPlutusData(e.value()))
                        .toList();
                yield "{" + String.join(", ", entries) + "}";
            }
        };
    }

    private String inferDisplayType(Object value) {
        if (value == null) return "Unit";
        if (value instanceof BigInteger) return "Integer";
        if (value instanceof Boolean) return "Boolean";
        if (value instanceof String) return "String";
        if (value instanceof byte[]) return "ByteString";
        if (value instanceof PlutusData) return "Data";
        return "Term";
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
