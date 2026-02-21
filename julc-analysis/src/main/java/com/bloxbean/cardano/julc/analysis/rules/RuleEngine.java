package com.bloxbean.cardano.julc.analysis.rules;

import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.decompiler.DecompileResult;

import java.util.List;

/**
 * Aggregates all rule-based security detectors and runs them on a decompiled script.
 */
public final class RuleEngine {

    private final List<SecurityRule> rules;

    public RuleEngine() {
        this.rules = List.of(
                new HardcodedCredentialRule(),
                new AuthorizationCheckRule(),
                new ValuePreservationRule(),
                new TimeValidationRule(),
                new UnboundedRecursionRule(),
                new DoubleSatisfactionRule(),
                new DatumIntegrityRule()
        );
    }

    public RuleEngine(List<SecurityRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<Finding> analyze(DecompileResult result) {
        return rules.stream()
                .flatMap(rule -> rule.analyze(result).stream())
                .toList();
    }

    public List<SecurityRule> rules() {
        return rules;
    }
}
