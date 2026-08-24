package com.bloxbean.julc.cli.cmd.verify;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyDslCommandTest {
    @Test
    void workerClasspathEntriesAreResolvedBeforeChangingWorkingDirectory() {
        String classpath = "build/specification-classes"
                + File.pathSeparator + "julc-cli/build/libs/julc.jar";

        String[] normalized = VerifyDslCommand.normalizeClasspath(classpath)
                .split(java.util.regex.Pattern.quote(File.pathSeparator));

        assertEquals(2, normalized.length);
        assertTrue(Path.of(normalized[0]).isAbsolute());
        assertTrue(Path.of(normalized[1]).isAbsolute());
        assertEquals(Path.of("build/specification-classes").toAbsolutePath().normalize(),
                Path.of(normalized[0]));
        assertEquals(Path.of("julc-cli/build/libs/julc.jar").toAbsolutePath().normalize(),
                Path.of(normalized[1]));
    }

    @Test
    void nativeLauncherCanCarryWorkerRuntimeInSpecificationClasspath() {
        String specification = "build/specification-classes"
                + File.pathSeparator + "julc-cli/build/libs/julc.jar";

        String workerClasspath = VerifyDslCommand.workerClasspath(specification, "");
        String[] entries = workerClasspath.split(
                java.util.regex.Pattern.quote(File.pathSeparator));

        assertEquals(2, entries.length);
        assertEquals(Path.of("build/specification-classes").toAbsolutePath().normalize(),
                Path.of(entries[0]));
        assertEquals(Path.of("julc-cli/build/libs/julc.jar").toAbsolutePath().normalize(),
                Path.of(entries[1]));
    }

    @Test
    void stableMetamodelDefaultsToSchemaTen() {
        var command = new CommandLine(new VerifyDslInitCommand());
        assertEquals("10", command.getCommandSpec()
                .findOption("--schema-version").defaultValue());
        assertTrue(command.getCommandSpec().usageMessage().description()[0]
                .contains("stable typed Java verification metamodel"));
    }
}
