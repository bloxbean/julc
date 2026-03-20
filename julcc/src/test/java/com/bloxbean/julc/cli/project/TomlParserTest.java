package com.bloxbean.julc.cli.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TomlParserTest {

    @Test
    void parseBasicToml() {
        var map = TomlParser.parseToMap("""
                [project]
                name = "my-validator"
                version = "0.2.0"
                compiler = "0.1.0"
                """);
        var project = map.get("project");
        assertEquals("my-validator", project.get("name"));
        assertEquals("0.2.0", project.get("version"));
        assertEquals("0.1.0", project.get("compiler"));
    }

    @Test
    void parseTomlIgnoresComments() {
        var map = TomlParser.parseToMap("""
                # This is a comment
                [project]
                name = "test"
                # Another comment
                version = "1.0"
                """);
        assertEquals("test", map.get("project").get("name"));
        assertEquals("1.0", map.get("project").get("version"));
    }

    @Test
    void parseTomlHandlesEmptyLines() {
        var map = TomlParser.parseToMap("""

                [project]

                name = "test"

                """);
        assertEquals("test", map.get("project").get("name"));
    }

    @Test
    void parseTomlHandlesUnquotedValues() {
        var map = TomlParser.parseToMap("""
                [project]
                name = unquoted
                """);
        assertEquals("unquoted", map.get("project").get("name"));
    }

    @Test
    void parseTomlFile(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("julc.toml");
        Files.writeString(toml, """
                [project]
                name = "my-app"
                version = "0.3.0"
                compiler = "0.1.0"
                """);
        JulcToml config = TomlParser.parse(toml);
        assertEquals("my-app", config.name());
        assertEquals("0.3.0", config.version());
        assertEquals("0.1.0", config.compiler());
    }

    @Test
    void julcTomlDefaultProject() {
        JulcToml toml = JulcToml.defaultProject("test");
        assertEquals("test", toml.name());
        assertEquals("0.1.0", toml.version());
        assertTrue(toml.toToml().contains("[project]"));
        assertTrue(toml.toToml().contains("name = \"test\""));
    }
}
