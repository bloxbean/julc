package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.core.flat.UplcFlatEncoder;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What ADR-068's lowering tests compare. The expected value is always the frozen JavaParser frontend's, on the same
 * source, at every level: nothing here is derived from the code under test. Each helper fails if the reference rejects
 * what it is meant to accept, so a fixture cannot go vacuous.
 */
public final class LoweringFixture {

    private LoweringFixture() {
    }

    private static CompilerOptions options(OptimizationLevel level, boolean sourceMap, CompilerFrontend frontend) {
        return new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(sourceMap).setFrontend(frontend);
    }

    private static CompileResult validator(String source, OptimizationLevel level, boolean sourceMap, CompilerFrontend frontend) {
        return new JulcCompiler(null, options(level, sourceMap, frontend)).compile(source, List.of());
    }

    private static CompileResult method(String source, String name, OptimizationLevel level, boolean sourceMap,
                                        CompilerFrontend frontend) {
        return method(source, name, level, sourceMap, frontend, null);
    }

    private static CompileResult method(String source, String name, OptimizationLevel level, boolean sourceMap,
                                        CompilerFrontend frontend, StdlibLookup stdlib) {
        return new JulcCompiler(stdlib, options(level, sourceMap, frontend)).compileMethod(source, name, List.of());
    }

    /** Same FLAT bytes as the reference at every level, with and without a source map. */
    public static void assertSameAsReference(String source) {
        for (var level : OptimizationLevel.values()) {
            for (boolean sourceMap : List.of(false, true)) {
                assertBytes(validator(source, level, sourceMap, CompilerFrontend.JAVAPARSER),
                        validator(source, level, sourceMap, CompilerFrontend.JAVAC), level + " sourceMap=" + sourceMap);
            }
        }
    }

    /** {@link #assertSameAsReference} for a {@code compileMethod} target. */
    public static void assertSameMethodAsReference(String source, String name) {
        for (var level : OptimizationLevel.values()) {
            for (boolean sourceMap : List.of(false, true)) {
                assertBytes(method(source, name, level, sourceMap, CompilerFrontend.JAVAPARSER),
                        method(source, name, level, sourceMap, CompilerFrontend.JAVAC), level + " sourceMap=" + sourceMap);
            }
        }
    }

    /** {@link #assertSameMethodAsReference(String, String)} with a stdlib. */
    public static void assertSameMethodAsReference(String source, String name, StdlibLookup stdlib) {
        for (var level : OptimizationLevel.values()) {
            for (boolean sourceMap : List.of(false, true)) {
                assertBytes(method(source, name, level, sourceMap, CompilerFrontend.JAVAPARSER, stdlib),
                        method(source, name, level, sourceMap, CompilerFrontend.JAVAC, stdlib), level + " sourceMap=" + sourceMap);
            }
        }
    }

    private static void assertBytes(CompileResult reference, CompileResult javac, String where) {
        var expected = UplcFlatEncoder.encodeProgram(reference.program());
        assertArrayEquals(expected, UplcFlatEncoder.encodeProgram(javac.program()), () -> where + ": expected "
                + HexFormat.of().formatHex(expected));
        assertEquals(reference.params(), javac.params(), where);
        assertEquals(reference.diagnostics(), javac.diagnostics(), where);
        if (reference.sourceMap() != null) assertEquals(positions(reference), positions(javac), where);
    }

    /** I7: each located UPLC term's line and column. The fragment is M2's rule, and the file name a D13 row. */
    private static Map<Integer, String> positions(CompileResult result) {
        var positions = new TreeMap<Integer, String>();
        result.sourceMap().toIndexed(result.program().term())
                .forEach((index, location) -> positions.put(index, location.line() + ":" + location.column()));
        return positions;
    }

    /** Both frontends reject with one code and one message, at one line and column. */
    public static void assertSameRejectionAsReference(String source) {
        assertSameRejection(frontend -> validator(source, OptimizationLevel.DEFAULT, false, frontend));
    }

    /** {@link #assertSameRejectionAsReference} for a {@code compileMethod} target. */
    public static void assertSameMethodRejectionAsReference(String source, String name) {
        assertSameRejection(frontend -> method(source, name, OptimizationLevel.DEFAULT, false, frontend));
    }

    static void assertSameRejection(Function<CompilerFrontend, CompileResult> compile) {
        var reference = assertThrows(CompilerException.class, () -> compile.apply(CompilerFrontend.JAVAPARSER));
        var javac = assertThrows(CompilerException.class, () -> compile.apply(CompilerFrontend.JAVAC));
        assertEquals(error(reference), error(javac), () -> javac.getMessage());
    }

    private static String error(CompilerException e) {
        var error = e.diagnostics().stream().filter(CompilerDiagnostic::isError).findFirst();
        return error.map(d -> d.code() + " " + d.message() + " @" + d.line() + ":" + d.column()).orElse(e.getMessage());
    }

    /** The compilation stops with {@code JULC0084} at {@code construct}, on the line given. The reference must not. */
    public static NotPorted assertNotPorted(String source, String construct, int line) {
        return assertNotPorted(() -> validator(source, OptimizationLevel.DEFAULT, false, CompilerFrontend.JAVAC), construct, line);
    }

    /** {@link #assertNotPorted} for a {@code compileMethod} target. */
    public static NotPorted assertMethodNotPorted(String source, String name, String construct, int line) {
        return assertNotPorted(() -> method(source, name, OptimizationLevel.DEFAULT, false, CompilerFrontend.JAVAC),
                construct, line);
    }

    private static NotPorted assertNotPorted(org.junit.jupiter.api.function.Executable compile, String construct, int line) {
        var e = assertThrows(NotPorted.class, compile);
        assertEquals(construct, e.construct(), e::getMessage);
        var diagnostic = e.diagnostics().getFirst();
        assertEquals("JULC0084", diagnostic.code());
        assertEquals(line, diagnostic.line(), e::getMessage);
        return e;
    }
}
