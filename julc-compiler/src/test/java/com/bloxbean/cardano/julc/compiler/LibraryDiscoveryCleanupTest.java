package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class LibraryDiscoveryCleanupTest {
    private static final List<String> LIBRARIES = List.of("ContextsLib", "ListsLib", "MapLib",
            "ValuesLib", "OutputLib", "MathLib", "IntervalLib", "CryptoLib", "ByteStringLib",
            "BitwiseLib", "AddressLib", "BlsLib", "NativeValueLib");

    @Test
    void allStdlibImportsResolveThroughBothCompilationEntrypoints() throws IOException {
        var imports = new StringBuilder();
        for (var name : LIBRARIES) {
            imports.append("import com.bloxbean.cardano.julc.stdlib.lib.").append(name).append(";\n");
        }
        byte[] methodBytes = null;
        byte[] validatorBytes = null;
        for (var prefix : List.of(imports.toString(),
                "import com.bloxbean.cardano.julc.stdlib.lib.*;\n", "")) {
            var method = compiler().compileMethod(prefix + """
                    import java.math.BigInteger;
                    class Sample {
                        static BigInteger run(BigInteger x) {
                            return MathLib.abs(x).add(Builtins.unIData(Builtins.iData(x))).add(BigInteger.ONE);
                        }
                    }
                    """, "run");
            assertCompiled(method);
            var actual = JulcVm.create("Java").evaluateWithArgs(method.program(), method.target().ledgerTarget(),
                    List.of(PlutusData.integer(-7)), null, EvalOptions.DEFAULT);
            assertEquals(Term.const_(Constant.integer(1)),
                    assertInstanceOf(EvalResult.Success.class, actual).resultTerm());
            var validator = compiler().compile(prefix + """
                    @SpendingValidator
                    class Sample {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return MathLib.abs(Builtins.unIData(redeemer)).signum() >= 0;
                        }
                    }
                    """);
            assertCompiled(validator);
            var mb = UplcFlatEncoder.encodeProgram(method.program());
            var vb = UplcFlatEncoder.encodeProgram(validator.program());
            assertHistoricalBytes("method", mb);
            assertHistoricalBytes("validator", vb);
            if (methodBytes != null) assertArrayEquals(methodBytes, mb);
            if (validatorBytes != null) assertArrayEquals(validatorBytes, vb);
            methodBytes = mb;
            validatorBytes = vb;
        }
    }

    @Test
    void explicitCustomLibraryAndBuiltinsNeedNoStdlibSourceList() {
        var library = """
                package example.cleanup;
                import java.math.BigInteger;
                @OnchainLibrary
                public class CustomMath {
                    public static BigInteger bump(BigInteger x) { return x.add(BigInteger.ONE); }
                }
                """;
        var source = """
                import java.math.BigInteger;
                import example.cleanup.CustomMath;
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                class Sample {
                    static BigInteger run(BigInteger x) {
                        return Builtins.unIData(Builtins.iData(CustomMath.bump(x))).add(BigInteger.ONE);
                    }
                }
                """;
        var method = compiler().compileMethod(source, "run", List.of(library));
        assertCompiled(method);
        var evaluated = JulcVm.create("Java").evaluateWithArgs(method.program(), method.target().ledgerTarget(),
                List.of(PlutusData.integer(40)), null, EvalOptions.DEFAULT);
        assertEquals(Term.const_(Constant.integer(42)),
                assertInstanceOf(EvalResult.Success.class, evaluated).resultTerm());
        assertCompiled(compiler().compile("""
                import example.cleanup.CustomMath;
                @SpendingValidator
                class Sample {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return CustomMath.bump(Builtins.unIData(redeemer)) > 0;
                    }
                }
                """, List.of(library)));
    }

    @Test
    void discoversCustomClasspathLibraryAndItsSamePackageDependency() {
        String imports = "import example.cleanup.DiscoveryWrapper;\n";
        var method = compiler().compileMethod(imports + """
                import java.math.BigInteger;
                class Sample {
                    static BigInteger run(BigInteger x) { return DiscoveryWrapper.twice(x); }
                }
                """, "run");
        assertCompiled(method);
        var evaluated = JulcVm.create("Java").evaluateWithArgs(method.program(), method.target().ledgerTarget(),
                List.of(PlutusData.integer(40)), null, EvalOptions.DEFAULT);
        assertEquals(Term.const_(Constant.integer(42)),
                assertInstanceOf(EvalResult.Success.class, evaluated).resultTerm());
        assertCompiled(compiler().compile(imports + """
                @SpendingValidator
                class Sample {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return DiscoveryWrapper.twice(Builtins.unIData(redeemer)) > 0;
                    }
                }
                """));
    }

    private static void assertHistoricalBytes(String name, byte[] bytes) throws IOException {
        try (var input = LibraryDiscoveryCleanupTest.class.getResourceAsStream(
                "/optimization/library-discovery-pre-cleanup.txt")) {
            assertNotNull(input);
            String golden = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(name + " ")).findFirst().orElseThrow();
            assertEquals(golden.substring(name.length() + 1), HexFormat.of().formatHex(bytes));
        }
    }

    private static JulcCompiler compiler() { return new JulcCompiler(StdlibRegistry.defaultRegistry()); }

    private static void assertCompiled(CompileResult result) {
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertNotNull(result.program());
    }
}
