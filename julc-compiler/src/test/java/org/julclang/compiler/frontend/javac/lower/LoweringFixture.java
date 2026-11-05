package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.core.flat.UplcFlatEncoder;

import org.junit.jupiter.api.function.Executable;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What ADR-068's lowering tests compare. The expected value is always the JavaParser frontend's on the same source, at every
 * level, frozen as data at M6, when that frontend left ({@link FrozenReference}): nothing here is derived from the code
 * under test. A source with no frozen row fails, so a fixture cannot go vacuous; a new test states what it expects.
 */
public final class LoweringFixture {

    /** Reads a message with its white space collapsed: the reference quotes a {@code switch} over several lines, javac's text on one. */
    public static final UnaryOperator<String> SPACING = text -> text.replaceAll("\\s+", " ");

    private LoweringFixture() {
    }

    private static CompilerOptions options(OptimizationLevel level, boolean sourceMap) {
        return new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(sourceMap);
    }

    private static CompileResult validator(String source, List<String> libraries, StdlibLookup stdlib, OptimizationLevel level, boolean sourceMap) {
        return new JulcCompiler(stdlib, options(level, sourceMap)).compile(source, libraries);
    }

    private static CompileResult method(String source, String name, List<String> libraries, StdlibLookup stdlib, OptimizationLevel level, boolean sourceMap) {
        return new JulcCompiler(stdlib, options(level, sourceMap)).compileMethod(source, name, libraries);
    }

    /** What a row of the frozen reference is keyed on: the entry, the sources and whether a stdlib lookup was given. */
    private static String what(String entry, String source, List<String> libraries, StdlibLookup stdlib) {
        return entry + "|" + source + "|" + libraries + "|" + (stdlib != null);
    }

    /** Same FLAT bytes as the reference at every level, with and without a source map. */
    public static void assertSameAsReference(String source) {
        assertSameAsReference(source, List.of(), null);
    }

    /** {@link #assertSameAsReference}, with {@code libraries} as the pool and {@code stdlib} as the lookup. */
    public static void assertSameAsReference(String source, List<String> libraries, StdlibLookup stdlib) {
        for (var level : OptimizationLevel.values()) {
            for (boolean sourceMap : List.of(false, true)) {
                FrozenReference.assertSame(what("validator", source, libraries, stdlib) + "|" + level + "|" + sourceMap,
                        observed(validator(source, libraries, stdlib, level, sourceMap)));
            }
        }
    }

    /** {@link #assertSameAsReference} for a {@code compileMethod} target. */
    public static void assertSameMethodAsReference(String source, String name) {
        assertSameMethodAsReference(source, name, List.of(), null);
    }

    /** {@link #assertSameMethodAsReference(String, String)} with a stdlib. */
    public static void assertSameMethodAsReference(String source, String name, StdlibLookup stdlib) {
        assertSameMethodAsReference(source, name, List.of(), stdlib);
    }

    /** {@link #assertSameMethodAsReference(String, String, StdlibLookup)}, with {@code libraries} as the pool. */
    public static void assertSameMethodAsReference(String source, String name, List<String> libraries, StdlibLookup stdlib) {
        for (var level : OptimizationLevel.values()) {
            for (boolean sourceMap : List.of(false, true)) {
                FrozenReference.assertSame(what("method " + name, source, libraries, stdlib) + "|" + level + "|" + sourceMap,
                        observed(method(source, name, libraries, stdlib, level, sourceMap)));
            }
        }
    }

    /** {@link #assertSameRejectionAsReference}, with {@code libraries} as the pool and {@code stdlib} as the lookup. */
    public static void assertSameRejectionAsReference(String source, List<String> libraries, StdlibLookup stdlib) {
        assertSameRejection(what("validator", source, libraries, stdlib),
                () -> validator(source, libraries, stdlib, OptimizationLevel.DEFAULT, false), UnaryOperator.identity());
    }

    /** {@link #assertSameMethodRejectionAsReference(String, String)}, with {@code libraries} as the pool. */
    public static void assertSameMethodRejectionAsReference(String source, String name, List<String> libraries, StdlibLookup stdlib) {
        assertSameMethodRejectionAsReference(source, name, libraries, stdlib, UnaryOperator.identity());
    }

    /** A validator's result, with {@code libraries}. */
    public static CompileResult validatorOf(String source, List<String> libraries, StdlibLookup stdlib, OptimizationLevel level) {
        return validator(source, libraries, stdlib, level, false);
    }

    /** The size of {@code name}'s script at {@code level}. */
    public static int methodSize(String source, String name, OptimizationLevel level, boolean sourceMap, StdlibLookup stdlib) {
        return UplcFlatEncoder.encodeProgram(method(source, name, List.of(), stdlib, level, sourceMap).program()).length;
    }

    /** The bytes, the {@code @Param} infos, the diagnostics and the located terms: what the reference and the javac frontend were compared on. */
    private static String observed(CompileResult result) {
        return HexFormat.of().formatHex(UplcFlatEncoder.encodeProgram(result.program())) + "|" + result.params() + "|"
                + result.diagnostics() + "|" + (result.sourceMap() == null ? "" : positions(result));
    }

    /** I7: each located UPLC term's line and column. The fragment is M2's rule, and the file name a D13 row. */
    private static Map<Integer, String> positions(CompileResult result) {
        var positions = new TreeMap<Integer, String>();
        result.sourceMap().toIndexed(result.program().term())
                .forEach((index, location) -> positions.put(index, location.line() + ":" + location.column()));
        return positions;
    }

    /** The rejection the reference gave: one code and one message, at one line and column. */
    public static void assertSameRejectionAsReference(String source) {
        assertSameRejectionAsReference(source, List.of(), null);
    }

    /** {@link #assertSameRejectionAsReference} for a {@code compileMethod} target. */
    public static void assertSameMethodRejectionAsReference(String source, String name) {
        assertSameMethodRejectionAsReference(source, name, List.of(), null);
    }

    /** {@link #assertSameMethodRejectionAsReference(String, String)} with a stdlib. */
    public static void assertSameMethodRejectionAsReference(String source, String name, StdlibLookup stdlib) {
        assertSameMethodRejectionAsReference(source, name, List.of(), stdlib);
    }

    /** {@link #assertSameMethodRejectionAsReference(String, String, StdlibLookup)}, with the message read through {@code message}. */
    public static void assertSameMethodRejectionAsReference(String source, String name, StdlibLookup stdlib, UnaryOperator<String> message) {
        assertSameMethodRejectionAsReference(source, name, List.of(), stdlib, message);
    }

    private static void assertSameMethodRejectionAsReference(String source, String name, List<String> libraries, StdlibLookup stdlib, UnaryOperator<String> message) {
        assertSameRejection(what("method " + name, source, libraries, stdlib),
                () -> method(source, name, libraries, stdlib, OptimizationLevel.DEFAULT, false), message);
    }

    private static void assertSameRejection(String what, Executable compile, UnaryOperator<String> message) {
        var javac = assertThrows(CompilerException.class, compile);
        FrozenReference.assertSame(what + "|rejected", message.apply(error(javac)));
    }

    /** The reference's rejection of {@code what}: it stays frozen, under the name a test gives it. */
    public static void assertSameRejection(String what, CompilerException javac) {
        FrozenReference.assertSame(what + "|rejected", error(javac));
    }

    private static String error(CompilerException e) {
        var error = e.diagnostics().stream().filter(CompilerDiagnostic::isError).findFirst();
        return error.map(d -> d.code() + " " + d.message() + " @" + d.line() + ":" + d.column()).orElse(e.getMessage());
    }

    /**
     * The rejection with {@code code} at {@code line}, not a stop: what was {@code JULC0084} until ADR-068 M5 has a code, or the
     * reference's words with their code (I2a).
     */
    public static CompilerDiagnostic assertMethodRejected(String source, String name, String code, int line) {
        var e = assertThrows(CompilerException.class, () -> method(source, name, List.of(), null, OptimizationLevel.DEFAULT, false));
        var diagnostic = e.diagnostics().stream().filter(CompilerDiagnostic::isError).findFirst().orElseThrow();
        assertEquals(code, diagnostic.code(), e::getMessage);
        assertEquals(line, diagnostic.line(), e::getMessage);
        return diagnostic;
    }
}
