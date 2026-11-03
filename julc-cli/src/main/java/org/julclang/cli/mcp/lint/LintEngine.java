package org.julclang.cli.mcp.lint;

import org.julclang.compiler.JavaSourceParser;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import org.julclang.cli.mcp.lint.rules.BannedParamTypesRule;
import org.julclang.cli.mcp.lint.rules.ByteStringLibOffChainRule;
import org.julclang.cli.mcp.lint.rules.DoubleHashRule;
import org.julclang.cli.mcp.lint.rules.EntrypointBigIntegerParamRule;
import org.julclang.cli.mcp.lint.rules.IncrementDecrementRule;
import org.julclang.cli.mcp.lint.rules.LambdaApplyRule;
import org.julclang.cli.mcp.lint.rules.MapReturnTypeRule;
import org.julclang.cli.mcp.lint.rules.MkConsPairListRule;
import org.julclang.cli.mcp.lint.rules.MutableVarRule;
import org.julclang.cli.mcp.lint.rules.OptionalMkSomeMkNoneRule;
import org.julclang.cli.mcp.lint.rules.RawPlutusDataAntiPatternRule;
import org.julclang.cli.mcp.lint.rules.ReturnInLoopRule;
import org.julclang.cli.mcp.lint.rules.SwitchFieldShadowRule;
import org.julclang.cli.mcp.lint.rules.Tuple2SwitchRule;
import org.julclang.cli.mcp.lint.rules.UninitializedVarRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

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

    /** How a source is read (ADR-068 M6): JavaParser's tree or javac's, with the same findings. */
    public enum Reader { JAVAPARSER, JAVAC }

    /** The reader {@link #LintEngine()} uses: JavaParser's until ADR-068 M5 makes javac the
     * default. The {@code julc.tools.reader} system property ({@code JAVAPARSER} or {@code JAVAC}) chooses it for every
     * tool, so that each module's tests run on either reading; it leaves at M6 with the JavaParser reading.
     */
    public static final Reader DEFAULT_READER = Reader.valueOf(System.getProperty("julc.tools.reader", "JAVAPARSER"));

    private final List<LintRule> rules;
    private final Reader reader;

    public LintEngine() {
        this(DEFAULT_RULES);
    }

    public LintEngine(List<LintRule> rules) {
        this(rules, DEFAULT_READER);
    }

    public LintEngine(List<LintRule> rules, Reader reader) {
        this.rules = List.copyOf(rules);
        this.reader = reader;
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
        return switch (reader) {
            case JAVAPARSER -> lintWithJavaParser(source);
            case JAVAC -> lintWithJavac(source);
        };
    }

    private List<LintFinding> lintWithJavaParser(String source) {
        // JavaParser 3.26.3 maxes out at JAVA_21; JuLC source uses sealed
        // interfaces + records (Java 21 features) so this is sufficient.
        var parseResult = JavaSourceParser.parse(source);
        if (parseResult.getResult().isEmpty()) {
            return List.of();
        }
        var cu = parseResult.getResult().get();
        return run(rule -> rule.check(cu));
    }

    /** javac's parser reads the source; a source it rejects has no findings, as an unreadable one has above. */
    private List<LintFinding> lintWithJavac(String source) {
        var syntax = JavacSyntax.read(source);
        if (syntax.hasErrors()) {
            return List.of();
        }
        return run(rule -> rule.check(syntax));
    }

    private List<LintFinding> run(Function<LintRule, List<LintFinding>> check) {
        var all = new ArrayList<LintFinding>();
        for (var rule : rules) {
            try {
                all.addAll(check.apply(rule));
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
