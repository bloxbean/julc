package org.julclang.decompiler;

import org.julclang.core.Program;
import org.julclang.core.text.UplcPrinter;
import org.julclang.decompiler.codegen.JavaCodeGenerator;
import org.julclang.decompiler.hir.HirTerm;
import org.julclang.decompiler.input.ScriptAnalyzer;
import org.julclang.decompiler.input.SharedRootBindings;
import org.julclang.decompiler.input.ScriptLoader;
import org.julclang.decompiler.lift.UplcLifter;
import org.julclang.decompiler.naming.NameAssigner;
import org.julclang.decompiler.typing.TypeInferenceEngine;

/**
 * Main facade for the JuLC decompiler.
 * <p>
 * Takes UPLC scripts in various formats and produces readable output at
 * multiple quality levels: disassembly, structured HIR, typed HIR, or full Java source.
 */
public final class JulcDecompiler {

    private JulcDecompiler() {}

    /**
     * Decompile a CBOR hex string with the given options.
     *
     * @param cborHex the hex-encoded script (double-CBOR, single-CBOR, or raw FLAT)
     * @param options decompilation options
     * @return the decompilation result
     */
    public static DecompileResult decompile(String cborHex, DecompileOptions options) {
        // Phase 1: Load and analyze
        Program program = ScriptLoader.fromHex(cborHex);
        String uplcText = UplcPrinter.print(program);
        // ADR-076: a JuLC script may bind shared closed values at its root; read it as it was before that sharing.
        Program analysed = SharedRootBindings.inline(program);
        ScriptAnalyzer.ScriptStats stats = stats(program, analysed);

        if (options.outputLevel() == DecompileOptions.OutputLevel.DISASSEMBLY) {
            return new DecompileResult(program, uplcText, stats);
        }

        // Phase 2: Lift to HIR
        HirTerm hir = UplcLifter.lift(analysed.term());

        if (options.outputLevel() == DecompileOptions.OutputLevel.STRUCTURED) {
            return new DecompileResult(program, uplcText, stats, hir, null);
        }

        // Phase 3: Type inference and naming
        hir = TypeInferenceEngine.infer(hir, stats);
        hir = NameAssigner.assignNames(hir, stats);

        if (options.outputLevel() == DecompileOptions.OutputLevel.TYPED) {
            return new DecompileResult(program, uplcText, stats, hir, null);
        }

        // Phase 4: Java code generation
        String javaSource = JavaCodeGenerator.generate(hir, stats);
        return new DecompileResult(program, uplcText, stats, hir, javaSource);
    }

    /**
     * The statistics of the script as deployed, {@code program}, with the arity read from {@code analysed}, the script
     * with its shared root bindings substituted back: those bindings sit before the validator's own lambdas.
     */
    private static ScriptAnalyzer.ScriptStats stats(Program program, Program analysed) {
        var stats = ScriptAnalyzer.analyze(program);
        return analysed == program ? stats : stats.withEstimatedArity(ScriptAnalyzer.estimatedArity(analysed));
    }

    /**
     * Decompile with default options (full Java output).
     */
    public static DecompileResult decompile(String cborHex) {
        return decompile(cborHex, DecompileOptions.defaults());
    }

    /**
     * Quick disassembly — UPLC text + statistics only.
     */
    public static DecompileResult disassemble(String cborHex) {
        return decompile(cborHex, DecompileOptions.disassembly());
    }

    /**
     * Decompile from a pre-parsed Program.
     */
    public static DecompileResult decompile(Program program, DecompileOptions options) {
        String uplcText = UplcPrinter.print(program);
        // ADR-076: a JuLC script may bind shared closed values at its root; read it as it was before that sharing.
        Program analysed = SharedRootBindings.inline(program);
        ScriptAnalyzer.ScriptStats stats = stats(program, analysed);

        if (options.outputLevel() == DecompileOptions.OutputLevel.DISASSEMBLY) {
            return new DecompileResult(program, uplcText, stats);
        }

        HirTerm hir = UplcLifter.lift(analysed.term());

        if (options.outputLevel() == DecompileOptions.OutputLevel.STRUCTURED) {
            return new DecompileResult(program, uplcText, stats, hir, null);
        }

        hir = TypeInferenceEngine.infer(hir, stats);
        hir = NameAssigner.assignNames(hir, stats);

        if (options.outputLevel() == DecompileOptions.OutputLevel.TYPED) {
            return new DecompileResult(program, uplcText, stats, hir, null);
        }

        String javaSource = JavaCodeGenerator.generate(hir, stats);
        return new DecompileResult(program, uplcText, stats, hir, javaSource);
    }
}
