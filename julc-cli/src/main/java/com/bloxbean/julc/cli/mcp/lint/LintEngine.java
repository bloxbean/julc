package com.bloxbean.julc.cli.mcp.lint;

import com.bloxbean.julc.cli.mcp.lint.rules.BannedParamTypesRule;
import com.bloxbean.julc.cli.mcp.lint.rules.ByteStringLibOffChainRule;
import com.bloxbean.julc.cli.mcp.lint.rules.DoubleHashRule;
import com.bloxbean.julc.cli.mcp.lint.rules.EntrypointBigIntegerParamRule;
import com.bloxbean.julc.cli.mcp.lint.rules.IncrementDecrementRule;
import com.bloxbean.julc.cli.mcp.lint.rules.LambdaApplyRule;
import com.bloxbean.julc.cli.mcp.lint.rules.MapReturnTypeRule;
import com.bloxbean.julc.cli.mcp.lint.rules.MkConsPairListRule;
import com.bloxbean.julc.cli.mcp.lint.rules.MutableVarRule;
import com.bloxbean.julc.cli.mcp.lint.rules.OptionalMkSomeMkNoneRule;
import com.bloxbean.julc.cli.mcp.lint.rules.RawPlutusDataAntiPatternRule;
import com.bloxbean.julc.cli.mcp.lint.rules.ReturnInLoopRule;
import com.bloxbean.julc.cli.mcp.lint.rules.SwitchFieldShadowRule;
import com.bloxbean.julc.cli.mcp.lint.rules.Tuple2SwitchRule;
import com.bloxbean.julc.cli.mcp.lint.rules.UninitializedVarRule;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Drives all registered lint rules against a single JuLC source string
 * and returns a sorted finding list.
 *
 * <p>The default rule set is intentionally compact — each one
 * targets a JuLC-specific failure mode that AI agents most commonly trip on.
 * Adding rules here automatically wires them into the {@code julc_lint}
 * MCP tool.
 */
public final class LintEngine {

    /** Default rule set. Order is irrelevant; findings are sorted by source position. */
    public static final List<LintRule> DEFAULT_RULES = List.of(
            // Phase C — original 6
            new OptionalMkSomeMkNoneRule(),
            new SwitchFieldShadowRule(),
            new RawPlutusDataAntiPatternRule(),
            new DoubleHashRule(),
            new MutableVarRule(),
            // Phase E — additional 10
            new ReturnInLoopRule(),
            new UninitializedVarRule(),
            new BannedParamTypesRule(),
            new EntrypointBigIntegerParamRule(),
            new LambdaApplyRule(),
            new Tuple2SwitchRule(),
            new ByteStringLibOffChainRule(),
            new MkConsPairListRule(),
            new MapReturnTypeRule(),
            new IncrementDecrementRule()
    );

    private final List<LintRule> rules;

    public LintEngine() {
        this(DEFAULT_RULES);
    }

    public LintEngine(List<LintRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<LintRule> rules() {
        return rules;
    }

    /**
     * Lint a JuLC source string and return all findings, sorted by line/column.
     *
     * <p>Returns an empty list on malformed source (the compile tool will
     * surface the parse error). Lint is best-effort.
     */
    public List<LintFinding> lint(String source) {
        // JavaParser 3.26.3 maxes out at JAVA_21; JuLC source uses sealed
        // interfaces + records (Java 21 features) so this is sufficient.
        var parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        var parseResult = parser.parse(source);
        if (parseResult.getResult().isEmpty()) {
            return List.of();
        }
        var cu = parseResult.getResult().get();
        var all = new ArrayList<LintFinding>();
        for (var rule : rules) {
            try {
                all.addAll(rule.check(cu));
            } catch (Exception e) {
                // A buggy lint rule must not crash the whole linter — surface as
                // an info-level finding so the maintainer notices but the agent
                // still gets the rest of the lint output.
                all.add(new LintFinding(rule.id(), null, "info",
                        "Lint rule " + rule.id() + " failed: " + e.getMessage(),
                        0, 0, null));
            }
        }
        all.sort(Comparator
                .comparingInt(LintFinding::line)
                .thenComparingInt(LintFinding::column));
        return all;
    }
}
