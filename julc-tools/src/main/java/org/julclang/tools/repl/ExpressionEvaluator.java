package org.julclang.tools.repl;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.frontend.javac.ReplExpressionWrapper;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.tools.service.ToolsService;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.julclang.vm.TermExtractor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates standalone expressions in the playground.
 * Wraps expressions with the typed wrapper JuLC's REPLs share (ADR-068 D9, {@link ReplExpressionWrapper}), compiles
 * via JulcCompiler.compileMethod(), evaluates via JulcVm, and extracts/formats the result. Integer arithmetic is
 * JuLC's, unbounded on-chain: {@code 2147483647 + 1} evaluates to 2147483648 where the JVM wraps it.
 */
public final class ExpressionEvaluator {

    private static final String CLASS_NAME = ReplExpressionWrapper.CLASS_NAME;
    private static final String METHOD_NAME = ReplExpressionWrapper.METHOD_NAME;

    /** The wrapper's imports: every stdlib class, the core types and the ledger. */
    private static final List<String> IMPORTS = imports();

    private static List<String> imports() {
        var imports = new ArrayList<>(StdlibRegistry.stdlibClassFqcns());
        imports.add("org.julclang.core.PlutusData");
        imports.add("org.julclang.core.types.*");
        imports.add("org.julclang.ledger.*");
        imports.add("java.math.BigInteger");
        imports.add("java.util.List");
        imports.add("java.util.Optional");
        return List.copyOf(imports);
    }

    private final JulcCompiler compiler;
    private final JulcVm vm;
    private final Map<String, LibrarySource> libraryPool;

    public ExpressionEvaluator() {
        this(LibrarySourceResolver.scanClasspathSources(ExpressionEvaluator.class.getClassLoader()));
    }

    private ExpressionEvaluator(Map<String, LibrarySource> classpathLibraries) {
        this(ToolsService.compilerFor(classpathLibraries), JulcVm.create(), classpathLibraries);
    }

    /**
     * Create an evaluator that shares an already configured compiler, VM and stdlib source pool.
     *
     * @param compiler    compiler created with the stdlib registry
     * @param vm          VM used for evaluation
     * @param libraryPool stdlib sources available for resolution
     */
    public ExpressionEvaluator(JulcCompiler compiler, JulcVm vm, Map<String, LibrarySource> libraryPool) {
        this.compiler = compiler;
        this.vm = vm;
        this.libraryPool = libraryPool;
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
        // javac types the expression first (ADR-068 D9): its rejection is the evaluator's error.
        List<String> resolvedLibs;
        try {
            resolvedLibs = LibrarySourceResolver.resolve(resolutionText(expression), libraryPool);
        } catch (Exception e) {
            resolvedLibs = List.of();
        }
        var wrapped = ReplExpressionWrapper.wrap(expression, IMPORTS, resolvedLibs);
        if (!wrapped.isAccepted()) {
            String errors = wrapped.errors().stream().map(d -> d.message()).reduce((a, b) -> a + "; " + b)
                    .orElse("Compilation failed");
            return new EvalExpressionResult(false, null, null, 0, 0, List.of(), "Compilation error: " + errors, null);
        }

        CompileResult cr;
        try {
            cr = compiler.compileMethod(wrapped.source(), METHOD_NAME, resolvedLibs);
        } catch (Exception e) {
            return new EvalExpressionResult(false, null, null, 0, 0, List.of(),
                    "Compilation error: " + e.getMessage(), null);
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
        var evalResult = vm.evaluate(
                cr.program(), cr.target().ledgerTarget(), null, EvalOptions.DEFAULT);
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

    /** The text the library resolver scans for the classes an expression uses. */
    private static String resolutionText(String expression) {
        var sb = new StringBuilder();
        for (String imp : IMPORTS) sb.append("import ").append(imp).append(";\n");
        return sb.append("class ").append(CLASS_NAME).append(" { Object x = ").append(expression).append("; }\n")
                .toString();
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
                if (c.fields().isEmpty()) yield "Constr(" + c.constructorTag() + ")";
                var fields = c.fields().stream().map(this::formatPlutusData).toList();
                yield "Constr(" + c.constructorTag() + ", [" + String.join(", ", fields) + "])";
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
