package com.bloxbean.cardano.julc.analysis;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.decompiler.DecompileOptions;
import com.bloxbean.cardano.julc.decompiler.JulcDecompiler;
import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborEncoder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecurityAnalyzerIntegrationTest {

    private static final HexFormat HEX = HexFormat.of();

    static final String ALWAYS_TRUE_SOURCE = """
            @Validator
            class AlwaysTrue {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;

    static final String VALUE_MANIPULATION_SOURCE = """
            import java.math.BigInteger;

            @Validator
            class ValueValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger a = 100;
                    BigInteger b = 50;
                    BigInteger result = a + b;
                    return result == 150;
                }
            }
            """;

    private Program compileValidator(String source) {
        var compiler = new JulcCompiler();
        var result = compiler.compile(source);
        if (result.hasErrors()) {
            fail("Compilation failed: " + result.diagnostics());
        }
        return result.program();
    }

    private String toDoubleCborHex(Program program) throws Exception {
        byte[] flat = UplcFlatEncoder.encodeProgram(program);
        byte[] inner = cborWrap(flat);
        byte[] outer = cborWrap(inner);
        return HEX.formatHex(outer);
    }

    private byte[] cborWrap(byte[] data) throws Exception {
        var baos = new ByteArrayOutputStream();
        new CborEncoder(baos).encode(new CborBuilder().add(data).build());
        return baos.toByteArray();
    }

    @Test
    void analyzeAlwaysTrueValidator_rulesOnly() {
        var program = compileValidator(ALWAYS_TRUE_SOURCE);
        var decompiled = JulcDecompiler.decompile(program, DecompileOptions.defaults());
        var report = SecurityAnalyzer.rulesOnly().analyze(decompiled);

        assertNotNull(report);
        assertNotNull(report.summary());
        assertNotNull(report.findings());
        assertNotNull(report.stats());

        // AlwaysTrue is trivially unsafe but it's so simple no rule may trigger
        System.out.println("AlwaysTrue findings: " + report.findings().size());
        System.out.println(report.prettyPrint());
    }

    @Test
    void analyzeAlwaysTrue_viaConvenience() throws Exception {
        var program = compileValidator(ALWAYS_TRUE_SOURCE);
        String hex = toDoubleCborHex(program);

        var report = SecurityAnalyzer.analyzeScript(hex, SecurityAnalyzer.rulesOnly());
        assertNotNull(report);
        assertNotNull(report.summary());
    }

    @Test
    void analyzeValueValidator_rulesOnly() {
        var program = compileValidator(VALUE_MANIPULATION_SOURCE);
        var decompiled = JulcDecompiler.decompile(program, DecompileOptions.defaults());
        var report = SecurityAnalyzer.rulesOnly().analyze(decompiled);

        assertNotNull(report);
        System.out.println("ValueValidator findings: " + report.findings().size());
        for (var f : report.findings()) {
            System.out.println("  [" + f.severity() + "] " + f.category() + ": " + f.title());
        }
    }

    @Test
    void analysisOptions_rulesOnly_skipsAi() {
        var program = compileValidator(ALWAYS_TRUE_SOURCE);
        var decompiled = JulcDecompiler.decompile(program, DecompileOptions.defaults());

        // Even with AI-enabled options, rulesOnly() analyzer has no AI
        var report = SecurityAnalyzer.rulesOnly().analyze(decompiled, AnalysisOptions.defaults());
        assertNotNull(report);
    }

    @Test
    void analysisOptions_skipCategories() {
        var program = compileValidator(ALWAYS_TRUE_SOURCE);
        var decompiled = JulcDecompiler.decompile(program, DecompileOptions.defaults());

        // Skip ALL categories — should get empty findings
        var options = new AnalysisOptions(true, false,
                Set.of(Category.values()));
        var report = SecurityAnalyzer.rulesOnly().analyze(decompiled, options);
        assertTrue(report.findings().isEmpty());
    }

    @Test
    void reportSummary_correctCounts() {
        var program = compileValidator(ALWAYS_TRUE_SOURCE);
        var decompiled = JulcDecompiler.decompile(program, DecompileOptions.defaults());
        var report = SecurityAnalyzer.rulesOnly().analyze(decompiled);

        var summary = report.summary();
        int totalFromCounts = summary.criticalCount() + summary.highCount()
                + summary.mediumCount() + summary.lowCount() + summary.infoCount();
        assertEquals(report.findings().size(), totalFromCounts);
    }

    @Test
    void reportPrettyPrint_containsHeader() {
        var program = compileValidator(ALWAYS_TRUE_SOURCE);
        var decompiled = JulcDecompiler.decompile(program, DecompileOptions.defaults());
        var report = SecurityAnalyzer.rulesOnly().analyze(decompiled);

        String printed = report.prettyPrint();
        assertTrue(printed.contains("Security Analysis Report"));
        assertTrue(printed.contains("Risk Level:"));
    }

    @Test
    void analysisReport_criticals_filters() {
        var program = compileValidator(ALWAYS_TRUE_SOURCE);
        var decompiled = JulcDecompiler.decompile(program, DecompileOptions.defaults());
        var report = SecurityAnalyzer.rulesOnly().analyze(decompiled);

        // criticals() should only return CRITICAL findings
        for (var f : report.criticals()) {
            assertEquals(Severity.CRITICAL, f.severity());
        }
        for (var f : report.highs()) {
            assertEquals(Severity.HIGH, f.severity());
        }
    }

    @Test
    void nullHir_rulesSkipped() {
        var program = compileValidator(ALWAYS_TRUE_SOURCE);
        // Use DISASSEMBLY level — no HIR
        var decompiled = JulcDecompiler.decompile(program, DecompileOptions.disassembly());
        assertNull(decompiled.hir());

        var report = SecurityAnalyzer.rulesOnly().analyze(decompiled);
        assertTrue(report.findings().isEmpty(), "Rules should be skipped when HIR is null");
    }
}
