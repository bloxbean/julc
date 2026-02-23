package com.bloxbean.cardano.julc.analyzer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliIntegrationTest {

    private static final String SAMPLE_HEX = TestData.SAMPLE_HEX;

    private CommandLine createCli() {
        return new CommandLine(new JulcAnalyzerCommand());
    }

    @Test
    void help_showsUsage() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));
        int code = cli.execute("--help");
        assertEquals(0, code);
        assertTrue(sw.toString().contains("julc-analyzer"));
    }

    @Test
    void version_showsVersion() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));
        int code = cli.execute("--version");
        assertEquals(0, code);
        assertTrue(sw.toString().contains("julc-analyzer"));
    }

    @Test
    void analyzeHelp_showsUsage() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));
        int code = cli.execute("analyze", "--help");
        assertEquals(0, code);
        assertTrue(sw.toString().contains("--compiled-code"));
        assertTrue(sw.toString().contains("--rules-only"));
    }

    @Test
    void analyzeRulesOnly_producesReport() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only");
        // Exit 0 or 1 depending on findings
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void analyzeRulesOnly_json() {
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only", "--json");
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void analyzeRulesOnly_noColor() {
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only", "--no-color");
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void analyzeRulesOnly_verbose() {
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only", "-v");
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void analyzeRulesOnly_quiet() {
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only", "-q");
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void analyzeFromFile(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("script.hex");
        Files.writeString(file, SAMPLE_HEX);
        var cli = createCli();
        int code = cli.execute("analyze", "-f", file.toString(), "--rules-only");
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void analyzeOutputToFile(@TempDir Path tempDir) throws Exception {
        var outFile = tempDir.resolve("report.txt");
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only",
                "-o", outFile.toString(), "--no-color");
        assertTrue(code == 0 || code == 1);
        assertTrue(Files.exists(outFile));
        var content = Files.readString(outFile);
        assertTrue(content.contains("Risk Level"));
    }

    @Test
    void analyzeOutputJsonToFile(@TempDir Path tempDir) throws Exception {
        var outFile = tempDir.resolve("report.json");
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only",
                "--json", "-o", outFile.toString());
        assertTrue(code == 0 || code == 1);
        assertTrue(Files.exists(outFile));
        var content = Files.readString(outFile);
        assertTrue(content.contains("\"riskLevel\""));
    }

    @Test
    void invalidHex_returnsError() {
        var cli = createCli();
        var errSw = new StringWriter();
        cli.setErr(new PrintWriter(errSw));
        int code = cli.execute("analyze", "--compiled-code", "not-valid-hex", "--rules-only");
        assertEquals(2, code);
    }

    @Test
    void skipCategories() {
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only",
                "--skip-categories", "HARDCODED_CREDENTIAL,GENERAL");
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void invalidCategory_returnsError() {
        var cli = createCli();
        var errSw = new StringWriter();
        cli.setErr(new PrintWriter(errSw));
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only",
                "--skip-categories", "NONEXISTENT");
        assertEquals(2, code);
    }

    @Test
    void noSubcommand_showsHelp() {
        var cli = createCli();
        var sw = new StringWriter();
        cli.setOut(new PrintWriter(sw));
        int code = cli.execute();
        assertEquals(0, code);
        assertTrue(sw.toString().contains("julc-analyzer"));
    }

    @Test
    void showCode_java(@TempDir Path tempDir) throws Exception {
        var outFile = tempDir.resolve("report.txt");
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only",
                "--show-code", "java", "--no-color", "-o", outFile.toString());
        assertTrue(code == 0 || code == 1);
        var content = Files.readString(outFile);
        assertTrue(content.contains("Decompiled Code"), "Should contain decompiled code header");
        assertTrue(content.contains("class"), "Should contain Java class");
    }

    @Test
    void showCode_uplc(@TempDir Path tempDir) throws Exception {
        var outFile = tempDir.resolve("report.txt");
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only",
                "--show-code", "uplc", "--no-color", "-o", outFile.toString());
        assertTrue(code == 0 || code == 1);
        var content = Files.readString(outFile);
        assertTrue(content.contains("Decompiled Code (uplc)"), "Should contain UPLC header");
    }

    @Test
    void showCode_default(@TempDir Path tempDir) throws Exception {
        var outFile = tempDir.resolve("report.txt");
        var cli = createCli();
        // --show-code= (with equals, empty value) triggers fallbackValue "java"
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only",
                "--no-color", "-o", outFile.toString(), "--show-code");
        assertTrue(code == 0 || code == 1);
        var content = Files.readString(outFile);
        assertTrue(content.contains("Decompiled Code (java)"), "Default should show java code");
    }

    @Test
    void showCode_json(@TempDir Path tempDir) throws Exception {
        var outFile = tempDir.resolve("report.json");
        var cli = createCli();
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only",
                "--show-code", "java", "--json", "-o", outFile.toString());
        assertTrue(code == 0 || code == 1);
        var content = Files.readString(outFile);
        assertTrue(content.contains("\"code\""), "JSON should contain 'code' field");
        assertTrue(content.contains("\"format\": \"java\""), "JSON code format should be 'java'");
        assertTrue(content.contains("\"content\":"), "JSON should contain 'content' field");
    }

    @Test
    void showCode_invalid() {
        var cli = createCli();
        var errSw = new StringWriter();
        cli.setErr(new PrintWriter(errSw));
        int code = cli.execute("analyze", "--compiled-code", SAMPLE_HEX, "--rules-only",
                "--show-code", "foo");
        assertEquals(2, code);
    }
}
