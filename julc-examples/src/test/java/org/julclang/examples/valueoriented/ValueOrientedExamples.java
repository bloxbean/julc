package org.julclang.examples.valueoriented;

import org.julclang.core.types.JulcList;

import java.math.BigInteger;

/**
 * The "after" examples of the Value-Oriented Contract Code page
 * ({@code docs/src/content/docs/best-practices/value-oriented-code.md}).
 * javac compiles this file, and {@link ValueOrientedExamplesTest} compiles each method to UPLC and
 * evaluates it, so the page's examples are both valid Java and checked on the VM.
 */
class ValueOrientedExamples {

    // region action-types
    sealed interface Action permits Only, Deposit {}
    record Only() implements Action {}
    record Deposit(BigInteger base) implements Action {}
    // endregion

    // 1. Compute a conditional value with an initializer.
    // region conditional-initializer
    static BigInteger countPositive(JulcList<BigInteger> xs) {
        BigInteger acc = BigInteger.ZERO;
        for (var x : xs) {
            BigInteger step = x.compareTo(BigInteger.ZERO) > 0 ? BigInteger.ONE : BigInteger.ZERO;
            acc = acc.add(step);
        }
        return acc;
    }
    // endregion

    // region conditional-limit
    static BigInteger limitFor(boolean vip) {
        BigInteger limit = vip ? BigInteger.valueOf(100) : BigInteger.TEN;
        return limit;
    }
    // endregion

    // 2. Yield the result of a switch arm.
    // region switch-yield
    static BigInteger armTotal(Action action, JulcList<BigInteger> xs) {
        BigInteger total = switch (action) {
            case Only o -> {
                BigInteger local = BigInteger.ZERO;
                for (var x : xs) {
                    local = local.add(x);
                }
                yield local;
            }
            case Deposit d -> d.base();
        };
        return total;
    }
    // endregion

    // 3. Copy a case-pattern binding before accumulating.
    // region pattern-copy
    static BigInteger depositTotal(Action action, JulcList<BigInteger> xs) {
        return switch (action) {
            case Deposit d -> {
                Deposit current = d;
                for (var x : xs) {
                    current = new Deposit(current.base().add(x));
                }
                yield current.base();
            }
            case Only o -> BigInteger.ZERO;
        };
    }
    // endregion

    // 4. Return a value from a helper instead of updating shared state.
    // region helper-value
    static BigInteger totalFee(JulcList<BigInteger> fees) {
        BigInteger total = BigInteger.ZERO;
        for (var fee : fees) {
            total = total.add(fee);
        }
        return total;
    }

    static boolean feeCovered(BigInteger paid, BigInteger fee) {
        return paid.compareTo(fee) >= 0;
    }

    static boolean paysFees(JulcList<BigInteger> fees, BigInteger paid) {
        return feeCovered(paid, totalFee(fees));
    }
    // endregion

    // 5. List operations.
    // region list-operations
    static boolean anyAbove(JulcList<BigInteger> xs, BigInteger limit) {
        return xs.any(x -> x.compareTo(limit) > 0);
    }

    static boolean allPresent(JulcList<BigInteger> required, JulcList<BigInteger> present) {
        return required.all(r -> present.any(p -> p.equals(r)));
    }
    // endregion

    // region map-typed
    static boolean anyDoubledAbove(JulcList<BigInteger> xs, BigInteger limit) {
        JulcList<BigInteger> doubled = xs.map(x -> x.multiply(BigInteger.TWO));
        return doubled.any(y -> y.compareTo(limit) > 0);
    }
    // endregion

    // region count-above
    static long countAbove(JulcList<BigInteger> xs, BigInteger limit) {
        return xs.filter(x -> x.compareTo(limit) > 0).size();
    }

    static long countAboveLoop(JulcList<BigInteger> xs, BigInteger limit) {
        long count = 0;
        for (var x : xs) {
            if (x.compareTo(limit) > 0) {
                count += 1;
            }
        }
        return count;
    }
    // endregion

    // 6. An ordinary accumulator loop with two accumulators.
    // region accumulators
    static boolean withinCap(JulcList<BigInteger> amounts, BigInteger cap) {
        BigInteger total = BigInteger.ZERO;
        long count = 0;
        for (var amount : amounts) {
            total = total.add(amount);
            count += 1;
        }
        return count > 0 && total.compareTo(cap) <= 0;
    }
    // endregion
}
