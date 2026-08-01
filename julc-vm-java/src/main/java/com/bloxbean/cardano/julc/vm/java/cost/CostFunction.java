package com.bloxbean.cardano.julc.vm.java.cost;

/**
 * Cost functions used by the Plutus cost model.
 * <p>
 * Each variant computes a cost from argument sizes using a specific formula
 * matching the Plutus specification.
 * <p>
 * All arithmetic saturates at {@code Long.MAX_VALUE}/{@code Long.MIN_VALUE},
 * matching Haskell's {@code SatInt} (and the Scalus {@code CostingInteger}).
 * Saturation is reachable through builtins whose arguments are costed
 * literally, e.g. {@code dropList} with a near-{@code maxBound} count.
 */
public sealed interface CostFunction {

    /**
     * Apply this cost function to the given argument sizes.
     *
     * @param sizes argument sizes (number depends on the function variant)
     * @return the computed cost
     */
    long apply(long... sizes);

    /** Saturating addition matching Haskell's {@code SatInt}. */
    static long satAdd(long a, long b) {
        long r = a + b;
        // Overflow iff both operands have the same sign and the result differs
        if (((a ^ r) & (b ^ r)) < 0) {
            return a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return r;
    }

    /** Saturating multiplication matching Haskell's {@code SatInt}. */
    static long satMul(long a, long b) {
        long r = a * b;
        if (a != 0 && (r / a != b
                || (a == Long.MIN_VALUE && b == -1)
                || (b == Long.MIN_VALUE && a == -1))) {
            return ((a > 0) == (b > 0)) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return r;
    }

    /** Saturating {@code intercept + slope * x}. */
    private static long linear(long intercept, long slope, long x) {
        return satAdd(intercept, satMul(slope, x));
    }

    /** Fixed cost independent of argument sizes. */
    record ConstantCost(long cost) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return cost;
        }
    }

    /** Cost is linear in the first argument: intercept + slope * x. */
    record LinearInX(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return linear(intercept, slope, sizes[0]);
        }
    }

    /** Cost is linear in the second argument: intercept + slope * y. */
    record LinearInY(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return linear(intercept, slope, sizes[1]);
        }
    }

    /** Cost is linear in the third argument: intercept + slope * z. */
    record LinearInZ(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return linear(intercept, slope, sizes[2]);
        }
    }

    /** Cost based on sum of two argument sizes: intercept + slope * (x + y). */
    record AddedSizes(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return linear(intercept, slope, satAdd(sizes[0], sizes[1]));
        }
    }

    /** Cost based on product of two argument sizes: intercept + slope * (x * y). */
    record MultipliedSizes(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return linear(intercept, slope, satMul(sizes[0], sizes[1]));
        }
    }

    /** Cost based on minimum of two argument sizes: intercept + slope * min(x, y). */
    record MinSize(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return linear(intercept, slope, Math.min(sizes[0], sizes[1]));
        }
    }

    /** Cost based on maximum of two argument sizes: intercept + slope * max(x, y). */
    record MaxSize(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return linear(intercept, slope, Math.max(sizes[0], sizes[1]));
        }
    }

    /**
     * Cost based on difference of two argument sizes: intercept + slope * max(minimum, x - y).
     * Matches Haskell: intercept + slope * max(minSize, x - y).
     */
    record SubtractedSizes(long intercept, long slope, long minimum) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            long diff = Math.max(minimum, sizes[0] - sizes[1]);
            return linear(intercept, slope, diff);
        }
    }

    /**
     * Constant cost when x < y (above diagonal), otherwise quadratic in x and y.
     * Used for division/modulo operations where numerator smaller than denominator is cheap.
     */
    record ConstAboveDiagonal(long constant, long c00, long c01, long c02,
                              long c10, long c11, long c20, long minimum) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            long x = sizes[0];
            long y = sizes[1];
            if (x < y) {
                return constant;
            }
            long result = c00;
            result = satAdd(result, satMul(c01, y));
            result = satAdd(result, satMul(c02, satMul(y, y)));
            result = satAdd(result, satMul(c10, x));
            result = satAdd(result, satMul(c11, satMul(x, y)));
            result = satAdd(result, satMul(c20, satMul(x, x)));
            return Math.max(minimum, result);
        }
    }

    /**
     * Evaluate the nested two-argument model with the larger size first and
     * the smaller size second. The retained constant is part of the ledger
     * parameter schema but is deliberately unused by the Haskell evaluator.
     */
    record AboveAndBelowDiagonal(long constant, CostFunction model) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return model.apply(Math.max(sizes[0], sizes[1]), Math.min(sizes[0], sizes[1]));
        }
    }

    /** General quadratic in two argument sizes, clamped to a minimum. */
    record QuadraticInXAndY(long c00, long c01, long c02,
                            long c10, long c11, long c20,
                            long minimum) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            long x = sizes[0];
            long y = sizes[1];
            long result = c00;
            result = satAdd(result, satMul(c01, y));
            result = satAdd(result, satMul(c02, satMul(y, y)));
            result = satAdd(result, satMul(c10, x));
            result = satAdd(result, satMul(c11, satMul(x, y)));
            result = satAdd(result, satMul(c20, satMul(x, x)));
            return Math.max(minimum, result);
        }
    }

    /**
     * Linear on diagonal (when x == y), constant off diagonal.
     * Used for equality comparisons on bytestrings/strings.
     */
    record LinearOnDiagonal(long constant, long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            if (sizes[0] != sizes[1]) {
                return constant;
            }
            return linear(intercept, slope, sizes[0]);
        }
    }

    /** Quadratic in y: c0 + c1*y + c2*y*y. Used for byteStringToInteger. */
    record QuadraticInY(long c0, long c1, long c2) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return quadratic(c0, c1, c2, sizes[1]);
        }
    }

    /** Quadratic in z: c0 + c1*z + c2*z*z. Used for integerToByteString. */
    record QuadraticInZ(long c0, long c1, long c2) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return quadratic(c0, c1, c2, sizes[2]);
        }
    }

    /** Saturating {@code c0 + c1*v + c2*v*v}. */
    private static long quadratic(long c0, long c1, long c2, long v) {
        return satAdd(satAdd(c0, satMul(c1, v)), satMul(c2, satMul(v, v)));
    }

    /**
     * For integerToByteString memory: if y == 0, intercept + slope * z; otherwise y.
     * Matches Plutus/Scalus, which test y == 0 (not y > 0) to select the linear form.
     */
    record LiteralInYOrLinearInZ(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            long y = sizes[1];
            if (y == 0) {
                return linear(intercept, slope, sizes[2]);
            }
            return y;
        }
    }

    /** Linear in max of y and z: intercept + slope * max(y, z). Used for bitwise memory. */
    record LinearInMaxYZ(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return linear(intercept, slope, Math.max(sizes[1], sizes[2]));
        }
    }

    /** Linear in y and z independently: intercept + slope1*y + slope2*z. Used for bitwise CPU. */
    record LinearInYAndZ(long intercept, long slope1, long slope2) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return satAdd(satAdd(intercept, satMul(slope1, sizes[1])), satMul(slope2, sizes[2]));
        }
    }

    /**
     * ExpModInteger CPU cost matching Haskell:
     * cost0 = c00 + c11 * exp * mod + c12 * exp * mod * mod.
     * If base > mod, apply 50% penalty: cost0 + cost0 / 2 (integer division).
     */
    record ExpModCost(long c00, long c11, long c12) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            long base = sizes[0];
            long exp = sizes[1];
            long mod = sizes[2];
            long expMod = satMul(exp, mod);
            long cost0 = satAdd(satAdd(c00, satMul(c11, expMod)), satMul(c12, satMul(expMod, mod)));
            if (base > mod) {
                return satAdd(cost0, cost0 / 2);
            }
            return cost0;
        }
    }

    /** Cost is linear in the fourth argument (index 3): intercept + slope * u. */
    record LinearInU(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return linear(intercept, slope, sizes[3]);
        }
    }

    /** Quadratic in the first argument: c0 + c1*x + c2*x*x. */
    record QuadraticInX(long c0, long c1, long c2) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return quadratic(c0, c1, c2, sizes[0]);
        }
    }

    /**
     * Constant cost when x &lt; y (above diagonal), linear in x and y below.
     * Used for ValueContains CPU.
     */
    record ConstAboveDiagonalLinear(long constant, long intercept, long slope1, long slope2) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            long x = sizes[0];
            long y = sizes[1];
            if (x < y) {
                return constant;
            }
            return satAdd(satAdd(intercept, satMul(slope1, x)), satMul(slope2, y));
        }
    }

    /**
     * Bilinear with interaction term: c00 + c10*x + c01*y + c11*x*y.
     * Used for UnionValue CPU.
     * <p>
     * Field order matches Haskell ParamName canonical ordering (c00, c10, c01, c11).
     */
    record WithInteractionInXAndY(long c00, long c10, long c01, long c11) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            long x = sizes[0];
            long y = sizes[1];
            long result = satAdd(c00, satMul(c10, x));
            result = satAdd(result, satMul(c01, y));
            return satAdd(result, satMul(c11, satMul(x, y)));
        }
    }
}
