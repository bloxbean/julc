package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.CompositeStdlibLookup;
import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.StdlibLookup;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLClassLoader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
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
    void discoversCustomClasspathLibraryAndItsSamePackageDependency(@TempDir Path root) throws IOException {
        var sources = root.resolve("META-INF/plutus-sources");
        Files.createDirectories(sources.resolve("example/cleanup"));
        for (var name : List.of("DiscoveryIncrement", "DiscoveryWrapper")) {
            try (var input = getClass().getResourceAsStream("/library-discovery/" + name + ".java")) {
                assertNotNull(input);
                Files.copy(input, sources.resolve("example/cleanup/" + name + ".java"));
            }
        }
        Files.writeString(sources.resolve("index.txt"),
                "example/cleanup/DiscoveryIncrement.java\nexample/cleanup/DiscoveryWrapper.java\n");
        var previousLoader = Thread.currentThread().getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, previousLoader)) {
            Thread.currentThread().setContextClassLoader(loader);
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
            String validator = imports + """
                    @SpendingValidator
                    class Sample {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return DiscoveryWrapper.twice(Builtins.unIData(redeemer)) > 0;
                        }
                    }
                    """;
            // compile(source) uses JulcCompiler's loader, not the context loader. Supply resolved
            // sources explicitly here instead of leaking fixtures into every test's classpath.
            var libraries = LibrarySourceResolver.resolve(validator, LibrarySourceResolver.scanClasspathSources(loader));
            assertCompiled(compiler().compile(validator, libraries));
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    @Test
    void importNamesComeFromCustomRegistrationsAndSurviveComposition() {
        var registry = new StdlibRegistry();
        registry.register("example.registry.RegistryOnly", "accept", args -> new PirTerm.Const(Constant.bool(true)));
        registry.register("AliasOnly", "accept", args -> new PirTerm.Const(Constant.bool(true)));
        assertEquals(Set.of("example.registry.RegistryOnly"), registry.registeredClassNames());
        var composite = new CompositeStdlibLookup(registry, StdlibRegistry.defaultRegistry());
        assertTrue(composite.registeredClassNames().containsAll(Set.of(
                "example.registry.RegistryOnly", "com.bloxbean.cardano.julc.stdlib.Builtins")));
        for (StdlibLookup lookup : List.of(registry, composite)) {
            var compiler = new JulcCompiler(lookup);
            String imports = "import example.registry.RegistryOnly;\n";
            var method = compiler.compileMethod(imports
                    + "class Sample { static boolean run() { return RegistryOnly.accept(); } }", "run", List.of());
            assertCompiled(method);
            var evaluated = JulcVm.create("Java").evaluate(method.program(), method.target().ledgerTarget(), null, EvalOptions.DEFAULT);
            assertEquals(Term.const_(Constant.bool(true)), assertInstanceOf(EvalResult.Success.class, evaluated).resultTerm());
            assertCompiled(compiler.compile(imports + """
                    @SpendingValidator class Sample {
                        @Entrypoint static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return RegistryOnly.accept();
                        }
                    }
                    """, List.of()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"method-floor", "validator-floor", "method-trace", "validator-trace",
            "method-any", "validator-any"})
    void registryOnlyMethodsWorkWithExplicitEmptyLibraryLists(String scenario) {
        String body = switch (scenario.substring(scenario.indexOf('-') + 1)) {
            case "floor" -> "return MathLib.floorDiv(Builtins.unIData(redeemer), 3) == -3;";
            case "trace" -> "ContextsLib.trace(\"registry\"); return true;";
            case "any" -> "return ListsLib.any(Builtins.unListData(redeemer), x -> Builtins.unIData(x) > 0);";
            default -> throw new IllegalArgumentException(scenario);
        };
        String imports = """
                import com.bloxbean.cardano.julc.stdlib.lib.MathLib;
                import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
                import com.bloxbean.cardano.julc.stdlib.lib.ListsLib;
                """;
        boolean methodPath = scenario.startsWith("method");
        String source = imports + (methodPath
                ? "class Sample { static boolean run(PlutusData redeemer) { " + body + " } }"
                : "@SpendingValidator class Sample { @Entrypoint static boolean validate(PlutusData redeemer, ScriptContext ctx) { "
                        + body + " } }");
        var compiled = methodPath ? compiler().compileMethod(source, "run", List.of())
                : compiler().compile(source, List.of());
        assertCompiled(compiled);
        if (methodPath) {
            var input = scenario.endsWith("any")
                    ? PlutusData.list(PlutusData.integer(-7), PlutusData.integer(2)) : PlutusData.integer(-7);
            var evaluated = JulcVm.create("Java").evaluateWithArgs(compiled.program(), compiled.target().ledgerTarget(),
                    List.of(input), null, EvalOptions.DEFAULT);
            var success = assertInstanceOf(EvalResult.Success.class, evaluated);
            assertEquals(Term.const_(Constant.bool(true)), success.resultTerm());
            assertEquals(scenario.endsWith("trace") ? List.of("registry") : List.of(), success.traces());
        }
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
