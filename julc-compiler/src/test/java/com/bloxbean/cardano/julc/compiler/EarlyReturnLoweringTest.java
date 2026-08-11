package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for early {@code return} inside if/else branches followed by
 * more statements (cardano-templates AMM finding: "a return false inside an
 * if/else block does not lower the way it reads, and silently let bad deposits
 * through").
 *
 * <p>Every expectation here is plain Java semantics: when a branch returns, the
 * statements after the if must not execute. The buggy lowering wrapped the
 * if/else in {@code Let("_if", ifExpr, rest)}, discarding the branch's return
 * value and unconditionally running {@code rest}.
 */
class EarlyReturnLoweringTest {

    static final JulcEval eval = JulcEval.forSource("""
            import java.math.BigInteger;

            class EarlyReturns {

                // Control: top-level early return, no else. Handled by the
                // if-fallthrough path and has always been correct.
                static boolean topLevelEarlyReturn(BigInteger a) {
                    if (a.compareTo(BigInteger.ZERO) <= 0) {
                        return false;
                    }
                    return true;
                }

                // Bug shape 1: else-branch returns, then-branch falls through.
                static boolean elseBranchReturns(BigInteger a) {
                    if (a.compareTo(BigInteger.ZERO) > 0) {
                        BigInteger positive = a;
                    } else {
                        return false;
                    }
                    return true;
                }

                // Bug shape 2: then-branch returns but an else exists.
                static boolean thenReturnsWithElse(BigInteger a) {
                    if (a.compareTo(BigInteger.ZERO) <= 0) {
                        return false;
                    } else {
                        BigInteger positive = a;
                    }
                    return true;
                }

                // Bug shape 3 (the AMM shape): return nested one level deeper,
                // inside a branch of an if that has code after it.
                static boolean nestedGuard(BigInteger a, BigInteger b) {
                    if (a.compareTo(BigInteger.ZERO) > 0) {
                        if (b.compareTo(BigInteger.ZERO) <= 0) {
                            return false;
                        }
                    } else {
                        return false;
                    }
                    return true;
                }

                // Bug shape 4: return mid-block with more statements after it
                // in the same branch block.
                static boolean returnThenMoreCodeInBranch(BigInteger a, BigInteger b) {
                    if (a.compareTo(BigInteger.ZERO) > 0) {
                        if (b.compareTo(BigInteger.ZERO) <= 0) {
                            return false;
                        }
                        BigInteger sum = a.add(b);
                    }
                    return true;
                }

                // Bug shape 5: a non-boolean return from one branch must also
                // short-circuit the value-producing statements after the if.
                static BigInteger integerEarlyReturn(BigInteger a) {
                    if (a.compareTo(BigInteger.ZERO) > 0) {
                        BigInteger positive = a;
                    } else {
                        return BigInteger.valueOf(-1);
                    }
                    return a.add(BigInteger.TEN);
                }

                // Four nested levels with returns in both then and else branches.
                // Every rejecting path must bypass the final return true.
                static boolean fourLevelMixedReturns(
                        BigInteger a, BigInteger b, BigInteger c, BigInteger d) {
                    if (a.compareTo(BigInteger.ZERO) > 0) {
                        if (b.compareTo(BigInteger.ZERO) > 0) {
                            if (c.compareTo(BigInteger.ZERO) > 0) {
                                if (d.compareTo(BigInteger.ZERO) <= 0) {
                                    return false;
                                }
                            } else {
                                return false;
                            }
                        } else {
                            return false;
                        }
                    } else {
                        return false;
                    }
                    return true;
                }

                // Each nesting level has a different fall-through continuation.
                // This verifies that continuations compose in lexical order and
                // that the deepest return bypasses all enclosing continuations.
                static BigInteger fourLevelContinuations(
                        BigInteger a, BigInteger b, BigInteger c, BigInteger d) {
                    if (a.compareTo(BigInteger.ZERO) > 0) {
                        if (b.compareTo(BigInteger.ZERO) > 0) {
                            if (c.compareTo(BigInteger.ZERO) > 0) {
                                if (d.compareTo(BigInteger.ZERO) < 0) {
                                    return BigInteger.valueOf(-1);
                                }
                                BigInteger afterD = d.add(BigInteger.ONE);
                                return afterD;
                            }
                            BigInteger afterC = c.add(BigInteger.TEN);
                            return afterC;
                        }
                        BigInteger afterB = b.add(BigInteger.valueOf(20));
                        return afterB;
                    }
                    return BigInteger.valueOf(100);
                }
            }
            """);

    @Test
    void topLevelEarlyReturnControl() {
        assertFalse(eval.call("topLevelEarlyReturn", BigInteger.valueOf(-5)).asBoolean());
        assertTrue(eval.call("topLevelEarlyReturn", BigInteger.valueOf(5)).asBoolean());
    }

    @Test
    void elseBranchReturnMustShortCircuit() {
        assertFalse(eval.call("elseBranchReturns", BigInteger.valueOf(-5)).asBoolean(),
                "else { return false; } must not fall through to the trailing return true");
        assertTrue(eval.call("elseBranchReturns", BigInteger.valueOf(5)).asBoolean());
    }

    @Test
    void thenBranchReturnWithElseMustShortCircuit() {
        assertFalse(eval.call("thenReturnsWithElse", BigInteger.valueOf(-5)).asBoolean(),
                "if (bad) { return false; } else { ... } must not fall through to return true");
        assertTrue(eval.call("thenReturnsWithElse", BigInteger.valueOf(5)).asBoolean());
    }

    @Test
    void nestedGuardMustShortCircuit() {
        assertFalse(eval.call("nestedGuard", BigInteger.valueOf(1), BigInteger.valueOf(-1)).asBoolean(),
                "nested return false inside a branch must reject");
        assertFalse(eval.call("nestedGuard", BigInteger.valueOf(-1), BigInteger.valueOf(1)).asBoolean());
        assertTrue(eval.call("nestedGuard", BigInteger.valueOf(1), BigInteger.valueOf(1)).asBoolean());
    }

    @Test
    void returnFollowedByCodeInSameBranchMustShortCircuit() {
        assertFalse(eval.call("returnThenMoreCodeInBranch", BigInteger.valueOf(1), BigInteger.valueOf(-1)).asBoolean(),
                "return false followed by more code in the same branch block must reject");
        assertTrue(eval.call("returnThenMoreCodeInBranch", BigInteger.valueOf(1), BigInteger.valueOf(1)).asBoolean());
        assertTrue(eval.call("returnThenMoreCodeInBranch", BigInteger.valueOf(-1), BigInteger.valueOf(1)).asBoolean());
    }

    @Test
    void nonBooleanEarlyReturnMustShortCircuit() {
        assertEquals(BigInteger.valueOf(-1),
                eval.call("integerEarlyReturn", BigInteger.valueOf(-5)).asInteger());
        assertEquals(BigInteger.valueOf(15),
                eval.call("integerEarlyReturn", BigInteger.valueOf(5)).asInteger());
    }

    @Test
    void fourLevelMixedReturnsMustShortCircuitAtEveryDepth() {
        assertFalse(eval.call("fourLevelMixedReturns",
                BigInteger.valueOf(-1), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE).asBoolean());
        assertFalse(eval.call("fourLevelMixedReturns",
                BigInteger.ONE, BigInteger.valueOf(-1), BigInteger.ONE, BigInteger.ONE).asBoolean());
        assertFalse(eval.call("fourLevelMixedReturns",
                BigInteger.ONE, BigInteger.ONE, BigInteger.valueOf(-1), BigInteger.ONE).asBoolean());
        assertFalse(eval.call("fourLevelMixedReturns",
                BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO).asBoolean());
        assertTrue(eval.call("fourLevelMixedReturns",
                BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ONE).asBoolean());
    }

    @Test
    void fourLevelFallThroughContinuationsComposeInLexicalOrder() {
        assertEquals(BigInteger.valueOf(100), eval.call("fourLevelContinuations",
                BigInteger.valueOf(-1), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE).asInteger());
        assertEquals(BigInteger.valueOf(18), eval.call("fourLevelContinuations",
                BigInteger.ONE, BigInteger.valueOf(-2), BigInteger.ONE, BigInteger.ONE).asInteger());
        assertEquals(BigInteger.valueOf(7), eval.call("fourLevelContinuations",
                BigInteger.ONE, BigInteger.ONE, BigInteger.valueOf(-3), BigInteger.ONE).asInteger());
        assertEquals(BigInteger.valueOf(-1), eval.call("fourLevelContinuations",
                BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.valueOf(-4)).asInteger());
        assertEquals(BigInteger.valueOf(6), eval.call("fourLevelContinuations",
                BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.valueOf(5)).asInteger());
    }
}
