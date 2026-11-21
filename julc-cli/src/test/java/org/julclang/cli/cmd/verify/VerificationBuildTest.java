package org.julclang.cli.cmd.verify;

import org.julclang.cli.cmd.blueprint.ArtifactCommand;
import org.julclang.cli.project.ProjectLayout;
import org.julclang.compiler.OptimizationLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationBuildTest {
    @TempDir
    Path tempDir;

    @Test
    void omittedLevelIsTheCompilerDefault() {
        assertEquals(OptimizationLevel.DEFAULT,
                VerificationBuild.forProfileId(null).level());
        assertEquals(OptimizationLevel.DEFAULT,
                VerificationBuild.forProfileId(null).compilerOptions().getOptimizationLevel());
    }

    @Test
    void explicitLevelReachesTheObservationalCompile() {
        var build = VerificationBuild.forProfileId("baseline");
        assertEquals(OptimizationLevel.BASELINE, build.level());
        assertEquals(OptimizationLevel.BASELINE,
                build.compilerOptions().getOptimizationLevel());
    }

    @Test
    void costedLevelIsAcceptedAndLeftToTheBuiltinPreflight() {
        assertEquals(OptimizationLevel.PV11_COSTED,
                VerificationBuild.forProfileId("pv11-costed").compilerOptions()
                        .getOptimizationLevel());
    }

    @Test
    void unknownLevelIsRejected() {
        assertThrows(RuntimeException.class, () -> VerificationBuild.forProfileId("fast"));
    }

    @Test
    void onlyPv11LevelsWarnAboutBlasterPreprocessing() {
        assertTrue(warning("pv11-safe").contains("--optimization baseline"));
        assertTrue(warning("pv11-costed").contains("--optimization baseline"));
        assertEquals("", warning("baseline"));
        assertEquals("", warning("none"));
    }

    @Test
    void buildAndObservationalCompileAgreeAtEachLevel() throws Exception {
        Path project = conditionalProject();

        DslContractLoader.load(project, "Gate", null, VerificationBuild.forProfileId("baseline"));
        String baseline = compiledCode(project);
        DslContractLoader.load(project, "Gate", null, VerificationBuild.forProfileId("pv11-safe"));
        String pv11Safe = compiledCode(project);

        // Each load succeeds only when plutus.json equals its own observational compile,
        // so both levels are bound consistently; the bytes must differ between levels.
        assertNotEquals(baseline, pv11Safe);
    }

    private Path conditionalProject() throws Exception {
        Path project = tempDir.resolve("gate");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("julc.toml"), """
                [project]
                name = "gate"
                version = "0.1.0"
                compiler = "local"
                """);
        Files.writeString(project.resolve("src/Gate.java"), """
                import java.math.BigInteger;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;
                import org.julclang.stdlib.lib.ContextsLib;
                @SpendingValidator class Gate {
                    record Datum(byte[] owner, BigInteger limit) {}
                    record Redeemer(BigInteger amount) {}
                    @Entrypoint static boolean validate(Datum datum, Redeemer redeemer,
                            ScriptContext ctx) {
                        if (redeemer.amount().compareTo(datum.limit()) > 0) {
                            return false;
                        }
                        return ContextsLib.signedBy(ctx.txInfo(), datum.owner());
                    }
                }
                """);
        return project;
    }

    private static String compiledCode(Path project) throws Exception {
        return ArtifactCommand.inspectForPurpose(
                ProjectLayout.plutusDir(project).resolve("plutus.json"), "Gate", "spend")
                .artifact().compiledCode();
    }

    private static String warning(String profileId) {
        var bytes = new ByteArrayOutputStream();
        VerificationBuild.forProfileId(profileId)
                .warnIfSlowForBlaster(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
