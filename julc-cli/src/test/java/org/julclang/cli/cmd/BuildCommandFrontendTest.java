package org.julclang.cli.cmd;

import org.julclang.cli.project.ProjectLayout;
import org.julclang.cli.scaffold.ProjectScaffolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * ADR-068 D4: {@code julc build --frontend}. The script from {@code javac} must equal the default's, and a construct not
 * ported must stop that frontend and not the default: the second proves the option reaches the compilation. A blueprint
 * needs a contract schema, which {@code javac} does not produce yet, so these pass {@code --no-blueprint}.
 */
class BuildCommandFrontendTest {

    private static Path project(Path dir, String name, String body) throws Exception {
        Path project = dir.resolve(name);
        ProjectScaffolder.scaffold(project, name);
        Files.writeString(ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java"), """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                public class AlwaysSucceeds {
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        %s
                    }
                }
                """.formatted(body));
        return project;
    }

    private static int build(Path project, String... options) {
        var arguments = new String[options.length + 2];
        arguments[0] = project.toString();
        arguments[1] = "--no-blueprint";
        System.arraycopy(options, 0, arguments, 2, options.length);
        return new CommandLine(new BuildCommand()).execute(arguments);
    }

    /** Every artifact of the build, by name: the build writes more than one for a validator. */
    private static Map<String, String> artifacts(Path project) throws Exception {
        var artifacts = new TreeMap<String, String>();
        try (var files = Files.list(ProjectLayout.plutusDir(project))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                artifacts.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        return artifacts;
    }

    /** D8: the CLI's pool, the project's library sources and the class path's, is javac's pool too: the same script. */
    @Test
    void aProjectLibraryBuildsAsOnTheReference(@TempDir Path dir) throws Exception {
        String body = "return FeeRules.ok(ctx.txInfo().fee()) && MathLib.pow(BigInteger.TWO, BigInteger.TEN).signum() > 0;";
        Path reference = project(dir, "reference", body);
        Path javac = project(dir, "javac", body);
        for (var project : List.of(reference, javac)) {
            var validator = ProjectLayout.srcDir(project).resolve("AlwaysSucceeds.java");
            Files.writeString(validator, Files.readString(validator).replace("import java.math.BigInteger;",
                    "import java.math.BigInteger;\nimport org.julclang.stdlib.lib.MathLib;"));
            Files.writeString(ProjectLayout.srcDir(project).resolve("FeeRules.java"), """
                    import java.math.BigInteger;
                    import org.julclang.stdlib.annotation.OnchainLibrary;

                    @OnchainLibrary
                    public class FeeRules {
                        static final BigInteger LIMIT = BigInteger.TWO;
                        public static boolean ok(BigInteger fee) { return fee.compareTo(LIMIT) > 0; }
                    }
                    """);
        }
        assertEquals(0, build(reference));
        assertEquals(0, build(javac, "--frontend", "javac"));
        assertFalse(artifacts(reference).isEmpty());
        assertEquals(artifacts(reference), artifacts(javac));
    }

    @Test
    void javacBuildsTheScriptTheDefaultDoes(@TempDir Path dir) throws Exception {
        String body = "return ctx.txInfo().fee().compareTo(BigInteger.TWO) > 0;";
        Path reference = project(dir, "reference", body);
        Path javac = project(dir, "javac", body);
        assertEquals(0, build(reference));
        assertEquals(0, build(javac, "--frontend", "javac"));
        assertFalse(artifacts(reference).isEmpty());
        assertEquals(artifacts(reference), artifacts(javac));
    }

    @Test
    void theOptionReachesTheCompilation(@TempDir Path dir) throws Exception {
        String body = "long fee = (short) ctx.txInfo().fee().longValue(); return fee > 0;";
        assertEquals(0, build(project(dir, "reference", body), "--frontend", "javaparser"));
        assertEquals(1, build(project(dir, "javac", body), "--frontend", "javac"));
    }

    @Test
    void anUnknownFrontendFailsTheBuild(@TempDir Path dir) throws Exception {
        assertEquals(1, build(project(dir, "unknown", "return true;"), "--frontend", "eclipse"));
    }
}
