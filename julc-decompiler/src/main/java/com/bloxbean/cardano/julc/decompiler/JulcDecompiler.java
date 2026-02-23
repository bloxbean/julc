package com.bloxbean.cardano.julc.decompiler;

import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.decompiler.codegen.JavaCodeGenerator;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;
import com.bloxbean.cardano.julc.decompiler.input.ScriptLoader;
import com.bloxbean.cardano.julc.decompiler.lift.UplcLifter;
import com.bloxbean.cardano.julc.decompiler.naming.NameAssigner;
import com.bloxbean.cardano.julc.decompiler.typing.TypeInferenceEngine;

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
        ScriptAnalyzer.ScriptStats stats = ScriptAnalyzer.analyze(program);

        if (options.outputLevel() == DecompileOptions.OutputLevel.DISASSEMBLY) {
            return new DecompileResult(program, uplcText, stats);
        }

        // Phase 2: Lift to HIR
        HirTerm hir = UplcLifter.lift(program.term());

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
        ScriptAnalyzer.ScriptStats stats = ScriptAnalyzer.analyze(program);

        if (options.outputLevel() == DecompileOptions.OutputLevel.DISASSEMBLY) {
            return new DecompileResult(program, uplcText, stats);
        }

        HirTerm hir = UplcLifter.lift(program.term());

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
