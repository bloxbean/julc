package com.bloxbean.julc.cli.mcp.lint.rules;

import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.mcp.lint.LintRule;
import com.github.javaparser.ast.CompilationUnit;
import java.util.List;

/**
 * Retired compatibility rule. Earlier AI guidance warned on
 * {@code @Param BigInteger}, but the compiler now decodes BigInteger params
 * through the normal parameter wrapper and production tests cover arithmetic.
 *
 * <p>The rule remains as a no-op so external code that references the class
 * does not break, but it is not part of {@link com.bloxbean.julc.cli.mcp.lint.LintEngine#DEFAULT_RULES}.
 */
public final class BigIntegerParamRule implements LintRule {

    @Override
    public String id() {
        return "JULC-LINT-BIGINT-PARAM";
    }

    @Override
    public String description() {
        return "@Param BigInteger is supported; this compatibility rule emits no findings";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        return List.of();
    }
}
