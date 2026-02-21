package com.bloxbean.cardano.julc.decompiler;

import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;

/**
 * The output of a decompilation run.
 *
 * @param program     the parsed UPLC program
 * @param uplcText    pretty-printed UPLC text (always present)
 * @param stats       structural analysis of the script
 * @param hir         the high-level IR (null if output level is DISASSEMBLY)
 * @param javaSource  generated Java source code (null unless output level is FULL_JAVA)
 */
public record DecompileResult(
        Program program,
        String uplcText,
        ScriptAnalyzer.ScriptStats stats,
        HirTerm hir,
        String javaSource
) {
    public DecompileResult(Program program, String uplcText, ScriptAnalyzer.ScriptStats stats) {
        this(program, uplcText, stats, null, null);
    }

    public DecompileResult withHir(HirTerm hir) {
        return new DecompileResult(program, uplcText, stats, hir, javaSource);
    }

    public DecompileResult withJavaSource(String javaSource) {
        return new DecompileResult(program, uplcText, stats, hir, javaSource);
    }
}
