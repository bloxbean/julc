package org.julclang.compiler.corpus;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.backend.LibraryRequest;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.pir.PirFormatter;
import org.julclang.core.PlutusData;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Compiles a fixed sample through every recorded entry point (and asks a provider for a specialization, and
 * evaluates a program) and prints one line per step: its FLAT bytes, its PIR, its outcome or its rejection.
 * {@link CorpusRecorderTest} runs it in child JVMs with and without {@value CorpusRecorder#PROPERTY} and requires the
 * same output.
 */
public final class CorpusRecorderProbe {

    static final String VALIDATOR = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class Sample {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger a = BigInteger.valueOf(3);
                    return a.add(BigInteger.ONE).equals(BigInteger.valueOf(4));
                }
            }
            """;

    static final String METHOD = """
            import java.math.BigInteger;

            class M {
                static BigInteger m(BigInteger a, BigInteger b) {
                    BigInteger s = a.add(a).add(a);
                    return s.add(b);
                }
            }
            """;

    static final String LIBRARY = """
            package sample;
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary public class Twice {
                public static BigInteger twice(BigInteger x) { return x.add(x); }
                public static <T> BigInteger count(JulcList<T> items) {
                    if (items.isEmpty()) return BigInteger.ZERO;
                    return count(items.tail()).add(BigInteger.ONE);
                }
            }
            """;

    static final String REJECTED = """
            class R {
                static double m(double x) { return x; }
            }
            """;

    /** The entry points this probe drives; {@link CorpusRecorderTest} requires a case for each. */
    static final List<String> ENTRIES = List.of("compile", "compileWithDetails", "compileForDebug", "compileContract",
            "compileContractWithDetails", "compileMethod", "compilePath", "compileContractPath", "JavaLibraryProvider");

    private CorpusRecorderProbe() {
    }

    public static void main(String[] args) throws Exception {
        var out = new StringBuilder();
        Path dir = Files.createTempDirectory("corpus-probe");
        Path file = dir.resolve("Sample.java");
        Files.writeString(file, VALIDATOR);
        Path siblings = Files.createDirectories(dir.resolve("siblings"));
        Path sibling = siblings.resolve("Sample.java");
        Files.writeString(sibling, VALIDATOR);
        Files.writeString(siblings.resolve("Twice.java"), LIBRARY);
        Path libraryFile = dir.resolve("Twice.java");
        Files.writeString(libraryFile, LIBRARY);
        for (var level : OptimizationLevel.values()) {
            var options = new CompilerOptions().setOptimizationLevel(level);
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
            var debug = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(true));
            line(out, level + " compile", () -> compiler.compile(VALIDATOR));
            line(out, level + " compile+libs", () -> compiler.compile(VALIDATOR, List.of()));
            line(out, level + " compileWithDetails", () -> compiler.compileWithDetails(VALIDATOR));
            line(out, level + " compileWithDetails+libs", () -> compiler.compileWithDetails(VALIDATOR, List.of()));
            line(out, level + " compileForDebug", () -> debug.compileForDebug(VALIDATOR).compileResult());
            line(out, level + " compileContract", () -> compiler.compileContract(VALIDATOR).compileResult());
            line(out, level + " compileContract+libs", () -> compiler.compileContract(VALIDATOR, List.of()).compileResult());
            line(out, level + " compileContractWithDetails",
                    () -> compiler.compileContractWithDetails(VALIDATOR).compileResult());
            line(out, level + " compileContractWithDetails+libs",
                    () -> compiler.compileContractWithDetails(VALIDATOR, List.of()).compileResult());
            line(out, level + " compileMethod", () -> compiler.compileMethod(METHOD, "m"));
            line(out, level + " compileMethod+libs", () -> compiler.compileMethod(METHOD, "m", List.of()));
            line(out, level + " compilePath", () -> compiler.compile(file));
            line(out, level + " compilePath+libs", () -> compiler.compile(file, List.of(libraryFile)));
            line(out, level + " compileFile", () -> compiler.compile(file.toFile()));
            line(out, level + " compileWithSiblings", () -> compiler.compileWithSiblings(sibling));
            line(out, level + " compileContractPath", () -> compiler.compileContract(file).compileResult());
            line(out, level + " compileContractPath+libs",
                    () -> compiler.compileContract(file, List.of(libraryFile)).compileResult());
            line(out, level + " rejected", () -> compiler.compileMethod(REJECTED, "m"));
            line(out, level + " customLookup",
                    () -> new JulcCompiler(new StdlibRegistry(), options).compileMethod(METHOD, "m"));
            line(out, level + " library", () -> {
                var provider = new JavaLibraryProvider(List.of(LIBRARY), StdlibRegistry.defaultRegistry(), options);
                return provider.describe("sample.Twice").toString();
            });
            line(out, level + " instantiate", () -> {
                var provider = new JavaLibraryProvider(List.of(LIBRARY), StdlibRegistry.defaultRegistry(), options);
                var request = new LibraryRequest.Instantiate("sample.Twice.count",
                        List.of(new LibraryType.Reference("Int", List.of())));
                var imports = provider.materialize(List.<LibraryRequest>of(request));
                return imports.binding(request).type() + " " + imports.definitions().values().stream()
                        .map(PirFormatter::format).toList();
            });
            line(out, level + " evaluate", () -> {
                var program = compiler.compileMethod(METHOD, "m").program();
                return CompilerTestVm.pv11().evaluateWithArgs(program, List.of(
                        new PlutusData.IntData(BigInteger.TWO), new PlutusData.IntData(BigInteger.ONE))).toString();
            });
        }
        System.out.print(out);
    }

    private static void line(StringBuilder out, String label, Callable<Object> compile) {
        Object result;
        try {
            result = compile.call();
        } catch (CompilerException e) {
            result = "rejected " + e.diagnostics().stream().map(d -> d.code() + " " + d.message()).toList();
        } catch (Exception e) {
            result = "failed " + e;
        }
        if (result instanceof CompileResult compiled) {
            result = HexFormat.of().formatHex(UplcFlatEncoder.encodeProgram(compiled.program()));
        }
        out.append(label).append(": ").append(result).append('\n');
    }
}
