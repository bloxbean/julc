package com.bloxbean.cardano.julc.analysis.ai;

import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;

import java.util.List;

/**
 * Interface for AI-powered security analysis of decompiled Cardano scripts.
 */
public interface AiAnalyzer {

    /**
     * Analyze the given Java source code for security vulnerabilities.
     *
     * @param javaSource decompiled Java source code
     * @param stats      script structural statistics
     * @return list of AI-detected findings
     */
    List<Finding> analyze(String javaSource, ScriptAnalyzer.ScriptStats stats);
}
