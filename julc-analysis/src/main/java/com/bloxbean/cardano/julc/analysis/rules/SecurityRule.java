package com.bloxbean.cardano.julc.analysis.rules;

import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;

import java.util.List;

/**
 * A deterministic security rule that inspects decompiled HIR for vulnerability patterns.
 */
public interface SecurityRule {

    /**
     * Human-readable name of this rule.
     */
    String name();

    /**
     * Analyze the decompiled result and return any findings.
     *
     * @param result the decompilation output (HIR + stats)
     * @return list of findings (empty if no issues)
     */
    List<Finding> analyze(DecompileResult result);
}
