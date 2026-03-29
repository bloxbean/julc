package com.bloxbean.julc.cli.repl;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;
import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.TermExtractor;

import java.math.BigInteger;
import java.util.*;

/**
 * Core evaluation engine for the REPL.
 * <p>
 * Wraps user expressions in a synthetic class, compiles via {@link JulcCompiler#compileMethod},
 * evaluates via {@link JulcVm}, and extracts results via {@link TermExtractor}.
 */
public final class ReplEngine {

    private final JulcCompiler compiler;
    private final JulcVm vm;
    private final Map<String, String> libraryPool;
    private final MethodDocExtractor docExtractor;
    private final List<String> userImports = new ArrayList<>();
    private boolean showBudget = true;

    public ReplEngine() {
        this.compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        this.vm = JulcVm.create();
        this.libraryPool = LibrarySourceResolver.scanClasspathSources(
                Thread.currentThread().getContextClassLoader());
        this.docExtractor = new MethodDocExtractor(libraryPool);
    }

    /**
     * Evaluate user input. Handles meta-commands and expressions.
     */
    public ReplResult evaluate(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // Meta-commands
        if (trimmed.startsWith(":")) {
            return handleMetaCommand(trimmed);
        }

        return evaluateExpression(trimmed);
    }

    /**
     * Evaluate an expression by wrapping, compiling, and running it.
     */
    ReplResult evaluateExpression(String expression) {
        // First attempt: inferred return type
        String source = ExpressionWrapper.wrap(expression, userImports);
        ReplResult result = compileAndEval(source);

        // Fallback: if compilation failed, try PlutusData cast wrapper
        if (result instanceof ReplResult.Error) {
            String fallbackSource = ExpressionWrapper.wrapAsData(expression, userImports);
            ReplResult fallbackResult = compileAndEval(fallbackSource);
            if (fallbackResult instanceof ReplResult.Success) {
                return fallbackResult;
            }
            // Return original error (more meaningful)
        }

        return result;
    }

    private ReplResult compileAndEval(String source) {
        try {
            // Resolve libraries needed by the expression
            var resolvedLibs = LibrarySourceResolver.resolve(source, libraryPool);

            // Compile
            CompileResult compileResult = compiler.compileMethod(
                    source, ExpressionWrapper.METHOD_NAME, resolvedLibs);

            if (compileResult.hasErrors()) {
                var msg = formatDiagnostics(compileResult.diagnostics());
                return new ReplResult.Error(msg);
            }

            // Evaluate
            EvalResult evalResult = vm.evaluate(compileResult.program());

            return switch (evalResult) {
                case EvalResult.Success s -> {
                    Object value = TermExtractor.extract(s.resultTerm());
                    String formatted = formatValue(value);
                    String uplc = compileResult.uplcFormatted();
                    String pir = compileResult.pirFormatted();
                    yield new ReplResult.Success(formatted, s.consumed(), s.traces(), uplc, pir);
                }
                case EvalResult.Failure f ->
                        new ReplResult.Error(f.error(), f.consumed(), f.traces());
                case EvalResult.BudgetExhausted b ->
                        new ReplResult.Error("Budget exhausted", b.consumed(), b.traces());
            };
        } catch (CompilerException e) {
            String msg = e.diagnostics().isEmpty()
                    ? e.getMessage()
                    : formatDiagnostics(e.diagnostics());
            return new ReplResult.Error(msg);
        } catch (Exception e) {
            return new ReplResult.Error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Compile-only evaluation for :type, :uplc, :pir commands.
     */
    ReplResult compileOnly(String expression) {
        String source = ExpressionWrapper.wrap(expression, userImports);
        try {
            var resolvedLibs = LibrarySourceResolver.resolve(source, libraryPool);
            CompileResult result = compiler.compileMethod(
                    source, ExpressionWrapper.METHOD_NAME, resolvedLibs);
            if (result.hasErrors()) {
                return new ReplResult.Error(formatDiagnostics(result.diagnostics()));
            }
            String uplc = result.uplcFormatted();
            String pir = result.pirFormatted();
            return new ReplResult.Success("(compiled)", ExBudget.ZERO, List.of(), uplc, pir);
        } catch (CompilerException e) {
            return new ReplResult.Error(e.getMessage());
        }
    }

    private ReplResult handleMetaCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        return switch (cmd) {
            case ":help", ":h" -> helpText();
            case ":quit", ":q", ":exit" -> null; // Signal to quit (handled by ReplCommand)
            case ":libs" -> listLibs();
            case ":methods" -> listMethods(arg);
            case ":type" -> {
                if (arg.isEmpty()) yield new ReplResult.Error(":type requires an expression");
                ReplResult r = compileOnly(arg);
                if (r instanceof ReplResult.Success s) {
                    yield new ReplResult.MetaOutput("Type: " + ExpressionWrapper.inferReturnType(arg));
                }
                yield r;
            }
            case ":uplc" -> {
                if (arg.isEmpty()) yield new ReplResult.Error(":uplc requires an expression");
                ReplResult r = evaluateExpression(arg);
                if (r instanceof ReplResult.Success s && s.uplc() != null) {
                    yield new ReplResult.MetaOutput(s.uplc());
                }
                yield r;
            }
            case ":pir" -> {
                if (arg.isEmpty()) yield new ReplResult.Error(":pir requires an expression");
                ReplResult r = compileOnly(arg);
                if (r instanceof ReplResult.Success s && s.pir() != null) {
                    yield new ReplResult.MetaOutput(s.pir());
                } else if (r instanceof ReplResult.Success) {
                    yield new ReplResult.MetaOutput("(PIR not available — compile with details)");
                }
                yield r;
            }
            case ":budget" -> {
                if (arg.equalsIgnoreCase("on")) {
                    showBudget = true;
                    yield new ReplResult.MetaOutput("Budget display: on");
                } else if (arg.equalsIgnoreCase("off")) {
                    showBudget = false;
                    yield new ReplResult.MetaOutput("Budget display: off");
                } else {
                    yield new ReplResult.MetaOutput("Budget display: " + (showBudget ? "on" : "off"));
                }
            }
            case ":import" -> {
                if (arg.isEmpty()) {
                    if (userImports.isEmpty()) {
                        yield new ReplResult.MetaOutput("No user imports.");
                    }
                    var sb = new StringBuilder("User imports:\n");
                    for (var imp : userImports) {
                        sb.append("  import ").append(imp).append(";\n");
                    }
                    yield new ReplResult.MetaOutput(sb.toString());
                }
                userImports.add(arg);
                yield new ReplResult.MetaOutput("Added import: " + arg);
            }
            case ":reset" -> {
                userImports.clear();
                yield new ReplResult.MetaOutput("Session reset.");
            }
            case ":clear" -> new ReplResult.MetaOutput("\033[H\033[2J");
            case ":doc" -> handleDoc(arg);
            default -> new ReplResult.Error("Unknown command: " + cmd + ". Type :help for available commands.");
        };
    }

    private ReplResult.MetaOutput helpText() {
        return new ReplResult.MetaOutput("""
                JuLC REPL Commands:
                  :help, :h           Show this help
                  :quit, :q, :exit    Exit (also 'quit', 'exit', Ctrl+D)
                  :libs               List auto-imported stdlib classes
                  :methods CLASS      List methods for a class (e.g., :methods ListsLib)
                  :doc CLASS.method   Show documentation for a method or class
                  :type EXPR          Show inferred type of expression
                  :uplc EXPR          Show UPLC representation
                  :pir EXPR           Show PIR intermediate representation
                  :budget on|off      Toggle budget display
                  :import CLASS       Add a user import for the session
                  :reset              Clear session state (user imports)
                  :clear              Clear screen

                Examples:
                  1 + 2                           => 3
                  MathLib.pow(2, 10)              => 1024
                  ListsLib.length(List.of(1,2,3)) => 3
                  Builtins.sha2_256(new byte[]{1,2,3})
                  :doc MathLib.pow
                  :methods ListsLib
                  :uplc 1 + 2""");
    }

    private ReplResult.MetaOutput listLibs() {
        var sb = new StringBuilder("Auto-imported stdlib libraries:\n");
        var descriptions = Map.ofEntries(
                Map.entry("Builtins", "Low-level UPLC builtins (sha2_256, iData, headList, ...)"),
                Map.entry("ListsLib", "List operations (length, head, tail, map, filter, ...)"),
                Map.entry("MapLib", "Map operations (lookup, member, insert, delete, ...)"),
                Map.entry("ValuesLib", "Value/multi-asset operations (leq, eq, singleton, ...)"),
                Map.entry("OutputLib", "Transaction output helpers (outputsAt, lovelacePaidTo, ...)"),
                Map.entry("ContextsLib", "ScriptContext accessors (txInfoMint, findOwnInput, ...)"),
                Map.entry("MathLib", "Math operations (pow, abs, max, min, divMod, ...)"),
                Map.entry("IntervalLib", "Interval operations (between, isEmpty, finiteBounds, ...)"),
                Map.entry("CryptoLib", "Cryptographic operations (ECDSA, Schnorr, RIPEMD-160)"),
                Map.entry("ByteStringLib", "ByteString operations (take, integerToByteString, ...)"),
                Map.entry("BitwiseLib", "Bitwise operations (and, or, xor, shift, rotate, ...)"),
                Map.entry("AddressLib", "Address operations (credentialHash, isScriptAddress, ...)"),
                Map.entry("BlsLib", "BLS12-381 curve operations"),
                Map.entry("NativeValueLib", "Native Value helpers")
        );

        for (String fqcn : StdlibRegistry.stdlibClassFqcns()) {
            String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            String desc = descriptions.getOrDefault(simpleName, "");
            sb.append("  ").append(String.format("%-16s", simpleName));
            if (!desc.isEmpty()) {
                sb.append(" - ").append(desc);
            }
            sb.append("\n");
        }
        return new ReplResult.MetaOutput(sb.toString());
    }

    private ReplResult listMethods(String className) {
        if (className.isEmpty()) {
            return new ReplResult.Error(":methods requires a class name (e.g., :methods ListsLib)");
        }
        // Merge methods from StdlibRegistry (PIR builtins) + source files (compiled libs)
        var methods = new TreeSet<String>();
        methods.addAll(StdlibRegistry.defaultRegistry().methodsForClass(className));

        // Scan source file for public static methods
        String source = libraryPool.get(className);
        if (source != null) {
            methods.addAll(ReplCompleter.extractPublicStaticMethods(source));
        }

        if (methods.isEmpty()) {
            return new ReplResult.Error("No methods found for class: " + className);
        }
        var sb = new StringBuilder(className).append(": ");
        sb.append(String.join(", ", methods));
        return new ReplResult.MetaOutput(sb.toString());
    }

    private ReplResult handleDoc(String arg) {
        if (arg.isEmpty()) {
            return new ReplResult.MetaOutput(
                    "Usage: :doc ClassName.method  or  :doc ClassName\n"
                    + "Example: :doc MathLib.pow, :doc MapLib");
        }

        if (arg.contains(".")) {
            // Method lookup: ClassName.methodName
            var doc = docExtractor.lookupMethod(arg);
            if (doc == null) {
                return new ReplResult.Error("No documentation found for: " + arg);
            }
            return new ReplResult.MetaOutput(MethodDocExtractor.formatMethodDoc(doc));
        } else {
            // Class lookup
            var doc = docExtractor.lookupClass(arg);
            if (doc == null) {
                return new ReplResult.Error("No documentation found for class: " + arg);
            }
            return new ReplResult.MetaOutput(MethodDocExtractor.formatClassDoc(doc));
        }
    }

    public boolean isShowBudget() {
        return showBudget;
    }

    public void setShowBudget(boolean showBudget) {
        this.showBudget = showBudget;
    }

    static String formatValue(Object value) {
        if (value == null) {
            return "()";
        }
        if (value instanceof byte[] bytes) {
            return "#" + bytesToHex(bytes);
        }
        if (value instanceof BigInteger bi) {
            return bi.toString();
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        if (value instanceof String s) {
            return "\"" + s + "\"";
        }
        if (value instanceof Optional<?> opt) {
            return opt.map(v -> "Some(" + formatValue(v) + ")")
                    .orElse("None");
        }
        if (value instanceof List<?> list) {
            var sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatValue(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof PlutusData data) {
            return formatPlutusData(data);
        }
        return value.toString();
    }

    private static String formatPlutusData(PlutusData data) {
        return switch (data) {
            case PlutusData.IntData i -> i.value().toString();
            case PlutusData.BytesData b -> "#" + bytesToHex(b.value());
            case PlutusData.ConstrData c -> {
                var sb = new StringBuilder("Constr(").append(c.tag());
                if (!c.fields().isEmpty()) {
                    sb.append(", [");
                    for (int i = 0; i < c.fields().size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(formatPlutusData(c.fields().get(i)));
                    }
                    sb.append("]");
                }
                sb.append(")");
                yield sb.toString();
            }
            case PlutusData.ListData l -> {
                var sb = new StringBuilder("[");
                for (int i = 0; i < l.items().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(formatPlutusData(l.items().get(i)));
                }
                sb.append("]");
                yield sb.toString();
            }
            case PlutusData.MapData m -> {
                var sb = new StringBuilder("{");
                for (int i = 0; i < m.entries().size(); i++) {
                    if (i > 0) sb.append(", ");
                    var entry = m.entries().get(i);
                    sb.append(formatPlutusData(entry.key()))
                            .append(": ")
                            .append(formatPlutusData(entry.value()));
                }
                sb.append("}");
                yield sb.toString();
            }
        };
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String formatDiagnostics(List<CompilerDiagnostic> diagnostics) {
        var sb = new StringBuilder();
        for (var d : diagnostics) {
            if (d.isError()) {
                sb.append(d.message());
                if (d.hasSuggestion()) {
                    sb.append("\n  suggestion: ").append(d.suggestion());
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }
}
