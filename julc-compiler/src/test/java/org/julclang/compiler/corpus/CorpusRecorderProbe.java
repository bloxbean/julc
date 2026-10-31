package org.julclang.compiler.corpus;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Compiles a fixed sample through every recorded entry point and prints one line per compilation: its FLAT bytes,
 * or its rejection. {@link CorpusRecorderTest} runs it in child JVMs with and without {@value CorpusRecorder#PROPERTY}
 * and requires the same output.
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
                    BigInteger s = BigInteger.ZERO;
                    for (int i = 0; i < 3; i++) { s = s.add(a); }
                    return s.add(b);
                }
            }
            """;

    static final String LIBRARY = """
            package sample;
            import java.math.BigInteger;
            @OnchainLibrary public class Twice {
                public static BigInteger twice(BigInteger x) { return x.add(x); }
            }
            """;

    static final String REJECTED = """
            class R {
                static double m(double x) { return x; }
            }
            """;

    private CorpusRecorderProbe() {
    }

    public static void main(String[] args) throws Exception {
        var out = new StringBuilder();
        Path dir = Files.createTempDirectory("corpus-probe");
        Path file = dir.resolve("Sample.java");
        Files.writeString(file, VALIDATOR);
        for (var level : OptimizationLevel.values()) {
            var options = new CompilerOptions().setOptimizationLevel(level);
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
            line(out, level + " compile", () -> compiler.compile(VALIDATOR));
            line(out, level + " compile+libs", () -> compiler.compile(VALIDATOR, List.of()));
            line(out, level + " compileContract", () -> compiler.compileContract(VALIDATOR).compileResult());
            line(out, level + " compileMethod", () -> compiler.compileMethod(METHOD, "m"));
            line(out, level + " compilePath", () -> compiler.compile(file));
            line(out, level + " rejected", () -> compiler.compileMethod(REJECTED, "m"));
            line(out, level + " library", () -> {
                var provider = new JavaLibraryProvider(List.of(LIBRARY), StdlibRegistry.defaultRegistry(), options);
                return provider.describe("sample.Twice").toString();
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
