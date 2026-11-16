package org.julclang.decompiler.input;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.codegen.SharedBindings;
import org.julclang.compiler.uplc.UplcOptimizer;
import org.julclang.core.DefaultFun;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.decompiler.JulcDecompiler;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-076: the decompiler reads a script with shared root bindings as it was before the sharing. */
class SharedRootBindingsTest {

    private static final Term HEAD = Term.force(Term.builtin(DefaultFun.HeadList));
    private static final Term TAIL = Term.force(Term.builtin(DefaultFun.TailList));

    /** {@code \datum \ctx. headList (tailList ctx)} with the forced builtins written out, or read from root binders. */
    private static Term validator(Term head, Term tail) {
        return Term.lam("datum", Term.lam("ctx", Term.apply(head, Term.apply(tail, Term.var(1)))));
    }

    @Test
    void sharedRootBindingsAreSubstitutedBack() {
        Term original = validator(HEAD, TAIL);
        // Root binders: #headList outermost, #tailList inside it; under the two lambdas they are indices 4 and 3.
        Term shared = Term.apply(Term.lam("#headList", Term.apply(Term.lam("#tailList",
                validator(Term.var(4), Term.var(3))), TAIL)), HEAD);
        var program = Program.plutusV3(shared);
        assertEquals(original, SharedRootBindings.inline(program).term());
        assertEquals(2, ScriptAnalyzer.analyze(SharedRootBindings.inline(program)).estimatedArity());
        assertEquals(0, ScriptAnalyzer.analyze(program).estimatedArity(), "the arity the decompiler saw before");
    }

    @Test
    void aScriptWithoutSharedBindingsIsUnchanged() {
        var program = Program.plutusV3(validator(HEAD, TAIL));
        assertSame(program, SharedRootBindings.inline(program));
        // A root let of something other than a shared value is not touched either.
        var other = Program.plutusV3(Term.apply(Term.lam("x", validator(HEAD, TAIL)), Term.builtin(DefaultFun.AddInteger)));
        assertSame(other, SharedRootBindings.inline(other));
    }

    /** A method whose loop and recursion each bind a fixpoint, so its script shares the Z combinator and builtins. */
    private static final String RECURSIVE = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            class Sums {
                static BigInteger sum(JulcList<BigInteger> xs, long n) {
                    if (n <= 0) return BigInteger.ZERO;
                    BigInteger acc = BigInteger.ZERO;
                    for (BigInteger x : xs) { acc = acc.add(x); }
                    return acc.add(sum(xs, n - 1));
                }
            }
            """;

    private static Program compile(Function<JulcCompiler, CompileResult> compile, boolean sharing) {
        var options = new CompilerOptions().setOptimizationLevel(OptimizationLevel.PV11_SAFE);
        if (!sharing) {
            options.disableOptimizationRule(SharedBindings.RULE);
            options.disableOptimizationRule(UplcOptimizer.SHARED_CLOSED_VALUES_RULE);
        }
        return compile.apply(new JulcCompiler(StdlibRegistry.defaultRegistry(), options)).program();
    }

    /** The issue #325 counter, a multi-validator that shares handler bindings and closed values at PV11_SAFE. */
    private static String plainCounter() throws IOException {
        return Files.readString(Path.of("..", "julc-compiler", "src", "test", "resources", "size", "PlainCounter.java.txt"));
    }

    private static String hex(Program program) {
        return HexFormat.of().formatHex(UplcFlatEncoder.encodeProgram(program));
    }

    @Test
    void aSerializedScriptIsReadAsItWasBeforeTheSharing() {
        var shared = compile(compiler -> compiler.compileMethod(RECURSIVE, "sum"), true);
        var unshared = compile(compiler -> compiler.compileMethod(RECURSIVE, "sum"), false);
        // Decoded from FLAT, every lambda has a fresh name, so the Z combinator is recognised by its indices alone.
        var decoded = ScriptLoader.fromHex(hex(shared));
        boolean sharesZ = false;
        for (Term term = decoded.term(); term instanceof Term.Apply(Term.Lam(var name, var body), var value); term = body) {
            sharesZ |= SharedRootBindings.isZ(value);
        }
        assertTrue(sharesZ, "the fixture shares the Z combinator");
        assertArrayEquals(UplcFlatEncoder.encodeProgram(unshared), UplcFlatEncoder.encodeProgram(SharedRootBindings.inline(decoded)));
        assertEquals(ScriptAnalyzer.estimatedArity(unshared), JulcDecompiler.disassemble(hex(shared)).stats().estimatedArity());
    }

    @Test
    void statisticsDescribeTheDeployedScriptAndTheArityTheValidatorTakes() throws IOException {
        String source = plainCounter();
        var shared = ScriptLoader.fromHex(hex(compile(compiler -> compiler.compile(source), true)));
        var stats = JulcDecompiler.disassemble(hex(shared)).stats();
        var deployed = ScriptAnalyzer.analyze(shared);
        assertEquals(deployed.withEstimatedArity(ScriptAnalyzer.estimatedArity(SharedRootBindings.inline(shared))), stats);
        assertEquals(deployed.totalNodes(), stats.totalNodes());
        assertEquals(deployed.maxDepth(), stats.maxDepth());
        assertTrue(stats.estimatedArity() > deployed.estimatedArity(), "the root bindings hide the validator's lambdas");
    }

    @Test
    void aSharingMultiValidatorHasTheArityOfTheProgramWithoutSharing() throws IOException {
        String source = plainCounter();
        var shared = compile(compiler -> compiler.compile(source), true);
        var unshared = compile(compiler -> compiler.compile(source), false);
        var stats = JulcDecompiler.disassemble(hex(shared)).stats();
        assertEquals(ScriptAnalyzer.estimatedArity(unshared), stats.estimatedArity());
        assertEquals(1, stats.estimatedArity(), "a multi-validator takes its script context");
    }
}
