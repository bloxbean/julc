package com.bloxbean.cardano.julc.vm.java.cost;

/**
 * Cost functions used by the Plutus cost model.
 * <p>
 * Each variant computes a cost from argument sizes using a specific formula
 * matching the Plutus specification.
 */
public sealed interface CostFunction {

    /**
     * Apply this cost function to the given argument sizes.
     *
     * @param sizes argument sizes (number depends on the function variant)
     * @return the computed cost
     */
    long apply(long... sizes);

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
            return intercept + slope * sizes[0];
        }
    }

    /** Cost is linear in the second argument: intercept + slope * y. */
    record LinearInY(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return intercept + slope * sizes[1];
        }
    }

    /** Cost is linear in the third argument: intercept + slope * z. */
    record LinearInZ(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return intercept + slope * sizes[2];
        }
    }

    /** Cost based on sum of two argument sizes: intercept + slope * (x + y). */
    record AddedSizes(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return intercept + slope * (sizes[0] + sizes[1]);
        }
    }

    /** Cost based on product of two argument sizes: intercept + slope * (x * y). */
    record MultipliedSizes(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return intercept + slope * (sizes[0] * sizes[1]);
        }
    }

    /** Cost based on minimum of two argument sizes: intercept + slope * min(x, y). */
    record MinSize(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return intercept + slope * Math.min(sizes[0], sizes[1]);
        }
    }

    /** Cost based on maximum of two argument sizes: intercept + slope * max(x, y). */
    record MaxSize(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return intercept + slope * Math.max(sizes[0], sizes[1]);
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
            return intercept + slope * diff;
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
            long result = c00 + c01 * y + c02 * y * y + c10 * x + c11 * x * y + c20 * x * x;
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
            return intercept + slope * sizes[0];
        }
    }

    /** Quadratic in y: c0 + c1*y + c2*y*y. Used for byteStringToInteger. */
    record QuadraticInY(long c0, long c1, long c2) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            long y = sizes[1];
            return c0 + c1 * y + c2 * y * y;
        }
    }

    /** Quadratic in z: c0 + c1*z + c2*z*z. Used for integerToByteString. */
    record QuadraticInZ(long c0, long c1, long c2) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            long z = sizes[2];
            return c0 + c1 * z + c2 * z * z;
        }
    }

    /**
     * For integerToByteString memory: if y > 0, use y; otherwise intercept + slope * z.
     */
    record LiteralInYOrLinearInZ(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            long y = sizes[1];
            if (y > 0) {
                return y;
            }
            return intercept + slope * sizes[2];
        }
    }

    /** Linear in max of y and z: intercept + slope * max(y, z). Used for bitwise memory. */
    record LinearInMaxYZ(long intercept, long slope) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return intercept + slope * Math.max(sizes[1], sizes[2]);
        }
    }

    /** Linear in y and z independently: intercept + slope1*y + slope2*z. Used for bitwise CPU. */
    record LinearInYAndZ(long intercept, long slope1, long slope2) implements CostFunction {
        @Override
        public long apply(long... sizes) {
            return intercept + slope1 * sizes[1] + slope2 * sizes[2];
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
            long cost0 = c00 + c11 * exp * mod + c12 * exp * mod * mod;
            if (base > mod) {
                return cost0 + cost0 / 2;
            }
            return cost0;
        }
    }
}
