package org.julclang.tools.service;

import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.frontend.javac.JavacGate;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.tools.debug.SourceDebugService;
import org.julclang.tools.model.CompileRequest;
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

    private String saved;
    private Map<String, LibrarySource> classpath;

    @BeforeEach
    void enforce() {
        saved = System.getProperty(JavacGate.MODE_PROPERTY);
        System.setProperty(JavacGate.MODE_PROPERTY, "enforce");
        classpath = new LinkedHashMap<>(LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader()));
        classpath.put("com.example.Fees", LibrarySourceResolver.librarySource(JAR_FEES));
    }

    @AfterEach
    void restore() {
        if (saved == null) System.clearProperty(JavacGate.MODE_PROPERTY);
        else System.setProperty(JavacGate.MODE_PROPERTY, saved);
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
