package org.julclang.clientlib;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.julclang.clientlib.ScriptResourcePaths.SCRIPT_SUFFIX;
import static org.julclang.clientlib.ScriptResourcePaths.SOURCE_MAP_SUFFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Issue #326: the paths the processor writes and the loader reads. */
class ScriptResourcePathsTest {

    @Test
    void aPackagedValidatorHasAQualifiedAndASimpleNamePath() {
        assertEquals(List.of("META-INF/plutus/com/b/VaultValidator.plutus.json",
                        "META-INF/plutus/VaultValidator.plutus.json"),
                ScriptResourcePaths.written("com.b.VaultValidator", "VaultValidator", SCRIPT_SUFFIX));
        assertEquals(List.of("META-INF/plutus/com/b/VaultValidator.sourcemap.json",
                        "META-INF/plutus/VaultValidator.sourcemap.json"),
                ScriptResourcePaths.written("com.b.VaultValidator", "VaultValidator", SOURCE_MAP_SUFFIX));
    }

    /** A nested class keeps its binary name, as {@link Class#getName()} gives it. */
    @Test
    void aNestedValidatorKeepsItsBinaryName() {
        assertEquals("META-INF/plutus/com/b/Outer$VaultValidator.plutus.json",
                ScriptResourcePaths.qualified("com.b.Outer$VaultValidator", SCRIPT_SUFFIX));
    }

    @Test
    void anUnnamedPackageValidatorHasOnePath() {
        assertEquals(List.of("META-INF/plutus/VaultValidator.plutus.json"),
                ScriptResourcePaths.written("VaultValidator", "VaultValidator", SCRIPT_SUFFIX));
    }
}
