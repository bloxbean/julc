package org.julclang.tools.service;

import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.tools.debug.SourceDebugService;
import org.julclang.tools.model.CompileRequest;
import org.julclang.tools.model.CompileResponse;
import org.julclang.tools.model.EvaluateRequest;
import org.julclang.tools.model.MockTransaction;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.julclang.tools.model.ScenarioOverrides;
import org.julclang.tools.model.SourceDebugModels.OpenRequest;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 D8, F551: the playground, Wasm and tools hosts (ToolsService, SourceDebugService, compilerFor). Their
 * class-path libraries are JAR sources and the editor's library is a project source, so a JAR library that the editor
 * library also declares is shadowed by it, not rejected as equal standing: on the compile, evaluate and source-debug
 * paths, with the gate enforced.
 */
class JavacGateToolsHostTest {

    static final String JAR_FEES = """
            package com.example;
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary
            public class Fees { public static BigInteger fee() { return BigInteger.TWO; } }
            """;
    static final String EDITOR_LIBRARY = """
            package com.example;
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary
            public class Rates { public static BigInteger rate() { return BigInteger.ONE; } }
            class Fees { static BigInteger fee() { return BigInteger.TEN; } }
            """;
    static final String VALIDATOR = """
            package com.example;
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            @SpendingValidator
            public class Gate {
                @Entrypoint
                public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return Rates.rate().add(Fees.fee()).signum() > 0;
                }
            }
            """;

    private Map<String, LibrarySource> classpath;

    @BeforeEach
    void scanTheClassPath() {
        classpath = new LinkedHashMap<>(LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader()));
        classpath.put("com.example.Fees", LibrarySourceResolver.librarySource(JAR_FEES));
    }

    static boolean equalStanding(Object body) {
        return String.valueOf(body).contains("equal standing");
    }

    @Test
    void compileAndEvaluateRankTheClassPathBelowTheEditor() {
        var tagged = new ToolsService(ToolsService.compilerFor(classpath), classpath, JulcVm::create);
        var compiled = tagged.compile(new CompileRequest(VALIDATOR, "java", EDITOR_LIBRARY)).body();
        assertFalse(equalStanding(compiled), String.valueOf(compiled));
        var evaluated = tagged.evaluate(new EvaluateRequest(VALIDATOR, "java", EDITOR_LIBRARY, Map.of(),
                new ScenarioOverrides(List.of(), null, null), Map.of(), null)).body();
        assertFalse(equalStanding(evaluated), String.valueOf(evaluated));
        // Untagged, the class-path copy and the editor's library are of equal standing: the gate rejects.
        var untagged = new ToolsService(new JulcCompiler(StdlibRegistry.defaultRegistry()), classpath, JulcVm::create);
        assertTrue(equalStanding(untagged.compile(new CompileRequest(VALIDATOR, "java", EDITOR_LIBRARY)).body()));
    }

    private static final String CAST = VALIDATOR.replace("Rates.rate().add(Fees.fee()).signum() > 0",
            "(boolean) (Object) ctx.txInfo().fee()");

    /** A subset rule reaches the tools at the default mode, with nothing set. */
    @Test
    void compileMeetsTheSubsetsRules() {
        var tools = new ToolsService(ToolsService.compilerFor(classpath), classpath, JulcVm::create);
        var compiled = tools.compile(new CompileRequest(CAST, "java", EDITOR_LIBRARY)).body();
        assertTrue(String.valueOf(compiled).contains("JULC0098"), String.valueOf(compiled));
    }

    @Test
    void sourceDebugMeetsTheSubsetsRules() {
        var opened = new SourceDebugService(classpath).open(new OpenRequest(CAST, EDITOR_LIBRARY, List.of(),
                spendTransaction(), 11, null, null, null, null, false)).body();
        assertTrue(String.valueOf(opened).contains("JULC0098"), String.valueOf(opened));
    }

    @Test
    void theToolsMeetTheCastAndNameRules() {
        var tools = new ToolsService(ToolsService.compilerFor(classpath), classpath, JulcVm::create);
        // These hosts compile on javac, the default from ADR-068 M5, and have no frontend option: the gate's name rules
        // (JULC0100, the gate-only JULC0101) are the JavaParser frontend's (D15). javac binds a record named Boolean to
        // the record, and a record's static method is ADR-067 D9's JULC0080.
        for (String[] rule : List.of(
                new String[]{"JULC0098", VALIDATOR.replace("Rates.rate().add(Fees.fee()).signum() > 0", "PlutusData.cast(ctx.txInfo().fee(), Boolean.class)")},
                new String[]{"JULC0080", VALIDATOR.replace("    @Entrypoint", "    record R(long a) { static boolean fromPlutusData(long x) { return false; } }\n    @Entrypoint")})) {
            var compiled = tools.compile(new CompileRequest(rule[1], "java", EDITOR_LIBRARY)).body();
            assertTrue(String.valueOf(compiled).contains(rule[0]), String.valueOf(compiled));
            var opened = new SourceDebugService(classpath).open(new OpenRequest(rule[1], EDITOR_LIBRARY, List.of(),
                    spendTransaction(), 11, null, null, null, null, false)).body();
            assertTrue(String.valueOf(opened).contains(rule[0]), String.valueOf(opened));
        }
    }

    /**
     * The review's F9: the {@code record Boolean} shape that is {@code JULC0100} on the JavaParser frontend's gate compiles on
     * javac, which binds the name to the record, and nothing reads it.
     */
    @Test
    void aRecordNamedLikeAJavaTypeCompilesOnJavac() {
        var tools = new ToolsService(ToolsService.compilerFor(classpath), classpath, JulcVm::create);
        var source = VALIDATOR.replace("    @Entrypoint", "    record Boolean(BigInteger a) { }\n    @Entrypoint");
        var compiled = tools.compile(new CompileRequest(source, "java", EDITOR_LIBRARY)).body();
        assertTrue(compiled instanceof CompileResponse response && response.uplcText() != null
                && !String.valueOf(compiled).contains("JULC0100"), String.valueOf(compiled));
    }

    @Test
    void theToolsMeetTheConstructorRule() {
        var tools = new ToolsService(ToolsService.compilerFor(classpath), classpath, JulcVm::create);
        for (String member : List.of(
                "record R(BigInteger a) { R { a = a.abs(); } }",
                "record R(BigInteger a) { R { a = BigInteger.ONE; } }",
                "record R(BigInteger a) { R() { this(BigInteger.ONE); } }",
                "static boolean OPEN = true; record R(BigInteger a) { R { OPEN = false; } }",
                "record R(BigInteger a) { R { a = a.max(BigInteger.ONE); } }")) {
            String source = VALIDATOR.replace("    @Entrypoint", "    " + member + "\n    @Entrypoint");
            var compiled = tools.compile(new CompileRequest(source, "java", EDITOR_LIBRARY)).body();
            assertTrue(String.valueOf(compiled).contains("JULC0099"), String.valueOf(compiled));
            var opened = new SourceDebugService(classpath).open(new OpenRequest(source, EDITOR_LIBRARY, List.of(),
                    spendTransaction(), 11, null, null, null, null, false)).body();
            assertTrue(String.valueOf(opened).contains("JULC0099"), String.valueOf(opened));
        }
    }

    @Test
    void sourceDebugRanksTheClassPathBelowTheEditor() {
        var opened = new SourceDebugService(classpath).open(new OpenRequest(VALIDATOR, EDITOR_LIBRARY, List.of(),
                spendTransaction(), 11, null, null, null, null, false)).body();
        assertFalse(equalStanding(opened), String.valueOf(opened));
    }

    private static MockTransaction spendTransaction() {
        var input = new MockTransaction.TxIn("11".repeat(32), 0L,
                new MockTransaction.Address("$self", null), new MockTransaction.Value("10000000", null),
                new MockTransaction.Datum("inline", new DataInput("uplc", "I 7"), null), null);
        return new MockTransaction(new MockTransaction.Purpose("spend", 0, null, null),
                new DataInput("uplc", "Constr 0 [I 1, B #abcd]"), List.of(input), null, null, "0", null, null, null,
                null, null, null, "22".repeat(32), null, null, null, null);
    }
}
