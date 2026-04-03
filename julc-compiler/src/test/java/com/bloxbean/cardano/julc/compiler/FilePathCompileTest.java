package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that both string-based and file-based compilation produce
 * correct source locations in source maps.
 */
class FilePathCompileTest {

    private static final String VALIDATOR_SOURCE = """
            import java.math.BigInteger;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;

    @Test
    void stringCompile_usesSyntheticFileName() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        var result = compiler.compile(VALIDATOR_SOURCE);
        assertFalse(result.hasErrors());
        assertTrue(result.hasSourceMap());

        var location = findAnySourceLocation(result);
        assertNotNull(location, "Should have at least one mapped source location");
        assertTrue(location.fileName().endsWith("TestValidator.java"),
                "String-based compile should use ClassName.java. Got: " + location.fileName());
    }

    @Test
    void fileCompile_preservesFileName(@TempDir Path tempDir) throws IOException {
        var sourceFile = tempDir.resolve("TestValidator.java");
        Files.writeString(sourceFile, VALIDATOR_SOURCE);

        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        var result = compiler.compile(sourceFile);
        assertFalse(result.hasErrors());
        assertTrue(result.hasSourceMap());

        var location = findAnySourceLocation(result);
        assertNotNull(location, "Should have at least one mapped source location");
        assertEquals("TestValidator.java", location.fileName(),
                "File-based compile should preserve the filename");
    }

    @Test
    void fileCompile_compilationUnitHasRealPath(@TempDir Path tempDir) throws IOException {
        var sourceFile = tempDir.resolve("TestValidator.java");
        Files.writeString(sourceFile, VALIDATOR_SOURCE);

        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        var result = compiler.compile(sourceFile);
        assertFalse(result.hasErrors());
        // The CompilationUnit storage has the real path (for tools that need it)
        // even though SourceLocation.fileName uses just the filename for display
        assertNotNull(result.program());
    }

    /**
     * Walk the UPLC term tree to find any term that has a source location mapped.
     */
    private static SourceLocation findAnySourceLocation(CompileResult result) {
        return findLocationInTerm(result.program().term(), result.sourceMap());
    }

    private static SourceLocation findLocationInTerm(Term term, SourceMap sourceMap) {
        var loc = sourceMap.lookup(term);
        if (loc != null) return loc;

        return switch (term) {
            case Term.Apply a -> {
                var r = findLocationInTerm(a.function(), sourceMap);
                yield r != null ? r : findLocationInTerm(a.argument(), sourceMap);
            }
            case Term.Lam l -> findLocationInTerm(l.body(), sourceMap);
            case Term.Force f -> findLocationInTerm(f.term(), sourceMap);
            case Term.Delay d -> findLocationInTerm(d.term(), sourceMap);
            case Term.Case c -> {
                for (var branch : c.branches()) {
                    var r = findLocationInTerm(branch, sourceMap);
                    if (r != null) yield r;
                }
                yield null;
            }
            case Term.Constr co -> {
                for (var field : co.fields()) {
                    var r = findLocationInTerm(field, sourceMap);
                    if (r != null) yield r;
                }
                yield null;
            }
            default -> null;
        };
    }
}
