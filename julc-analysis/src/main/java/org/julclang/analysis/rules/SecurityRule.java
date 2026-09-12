package org.julclang.analysis.rules;

import org.julclang.analysis.Finding;
import org.julclang.decompiler.DecompileResult;

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
