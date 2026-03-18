package com.bloxbean.cardano.julc.vm.java.cost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CostFunctionTest {

    @Test
    void constantCost() {
        var f = new CostFunction.ConstantCost(42);
        assertEquals(42, f.apply());
        assertEquals(42, f.apply(100, 200)); // sizes ignored
    }

    @Test
    void linearInX() {
        var f = new CostFunction.LinearInX(100, 5);
        assertEquals(100 + 5 * 10, f.apply(10));
        assertEquals(100 + 5 * 0, f.apply(0));
    }

    @Test
    void linearInY() {
        var f = new CostFunction.LinearInY(200, 3);
        assertEquals(200 + 3 * 7, f.apply(999, 7));
    }

    @Test
    void linearInZ() {
        var f = new CostFunction.LinearInZ(50, 2);
        assertEquals(50 + 2 * 8, f.apply(0, 0, 8));
    }

    @Test
    void addedSizes() {
        var f = new CostFunction.AddedSizes(1000, 173);
        assertEquals(1000 + 173 * (10 + 20), f.apply(10, 20));
    }

    @Test
    void multipliedSizes() {
        var f = new CostFunction.MultipliedSizes(90434, 519);
        assertEquals(90434 + 519 * (3 * 4), f.apply(3, 4));
    }

    @Test
    void minSize() {
        var f = new CostFunction.MinSize(51775, 558);
        assertEquals(51775 + 558 * 3, f.apply(3, 10));
        assertEquals(51775 + 558 * 3, f.apply(10, 3));
    }

    @Test
    void maxSize() {
        var f = new CostFunction.MaxSize(100788, 420);
        assertEquals(100788 + 420 * 10, f.apply(3, 10));
        assertEquals(100788 + 420 * 10, f.apply(10, 3));
    }

    @Test
    void subtractedSizes() {
        // Haskell formula: intercept + slope * max(minimum, x - y)
        var f = new CostFunction.SubtractedSizes(0, 1, 1);
        assertEquals(7, f.apply(10, 3));  // 0 + 1 * max(1, 10-3) = 7
        assertEquals(1, f.apply(3, 10));  // 0 + 1 * max(1, 3-10) = max(1, -7) = 1
    }

    @Test
    void subtractedSizesWithInterceptAndSlope() {
        var f = new CostFunction.SubtractedSizes(100, 5, 3);
        // x > y: 100 + 5 * max(3, 10-2) = 100 + 5*8 = 140
        assertEquals(140, f.apply(10, 2));
        // x < y: 100 + 5 * max(3, 2-10) = 100 + 5*3 = 115 (minimum kicks in)
        assertEquals(115, f.apply(2, 10));
        // difference < minimum: 100 + 5 * max(3, 5-4) = 100 + 5*3 = 115
        assertEquals(115, f.apply(5, 4));
        // difference == minimum: 100 + 5 * max(3, 6-3) = 100 + 5*3 = 115
        assertEquals(115, f.apply(6, 3));
        // difference > minimum: 100 + 5 * max(3, 10-3) = 100 + 5*7 = 135
        assertEquals(135, f.apply(10, 3));
    }

    @Test
    void constAboveDiagonal() {
        var f = new CostFunction.ConstAboveDiagonal(85848, 123203, 7305, -900, 1716, 549, 57, 85848);
        // When x < y, returns constant (above diagonal = numerator smaller than denominator)
        assertEquals(85848, f.apply(5, 10));
        // When x >= y, returns quadratic formula (clamped to minimum)
        long result = f.apply(10, 1);
        assertTrue(result >= 85848, "Result should be at least minimum: " + result);
    }

    @Test
    void constAboveDiagonalAllPaths() {
        var f = new CostFunction.ConstAboveDiagonal(100, 10, 2, 3, 4, 5, 6, 50);
        // x < y: constant
        assertEquals(100, f.apply(1, 5));
        // x == y (on diagonal): quadratic with x=y=3
        // 10 + 2*3 + 3*9 + 4*3 + 5*9 + 6*9 = 10+6+27+12+45+54 = 154
        assertEquals(154, f.apply(3, 3));
        // x > y (below diagonal): quadratic with x=5, y=1
        // 10 + 2*1 + 3*1 + 4*5 + 5*5 + 6*25 = 10+2+3+20+25+150 = 210
        assertEquals(210, f.apply(5, 1));
    }

    @Test
    void linearOnDiagonal() {
        var f = new CostFunction.LinearOnDiagonal(24548, 29498, 38);
        // Off diagonal
        assertEquals(24548, f.apply(5, 10));
        // On diagonal
        assertEquals(29498 + 38 * 5, f.apply(5, 5));
    }

    @Test
    void quadraticInY() {
        var f = new CostFunction.QuadraticInY(1000, 100, 10);
        long y = 5;
        assertEquals(1000 + 100 * 5 + 10 * 25, f.apply(0, y));
    }

    @Test
    void quadraticInZ() {
        var f = new CostFunction.QuadraticInZ(1000, 100, 10);
        long z = 3;
        assertEquals(1000 + 100 * 3 + 10 * 9, f.apply(0, 0, z));
    }

    @Test
    void literalInYOrLinearInZ() {
        var f = new CostFunction.LiteralInYOrLinearInZ(0, 1);
        // y > 0: use y
        assertEquals(10, f.apply(0, 10, 5));
        // y == 0: use intercept + slope * z
        assertEquals(5, f.apply(0, 0, 5));
    }

    @Test
    void linearInMaxYZ() {
        var f = new CostFunction.LinearInMaxYZ(0, 1);
        assertEquals(10, f.apply(0, 10, 5));
        assertEquals(10, f.apply(0, 5, 10));
    }

    @Test
    void linearInYAndZ() {
        var f = new CostFunction.LinearInYAndZ(100, 10, 20);
        assertEquals(100 + 10 * 3 + 20 * 5, f.apply(0, 3, 5));
    }

    @Test
    void expModCostBaseLeqMod() {
        // base <= mod: no penalty
        // cost0 = c00 + c11 * exp * mod + c12 * exp * mod * mod
        var f = new CostFunction.ExpModCost(607153, 231697, 53144);
        // base=2, exp=3, mod=4: 607153 + 231697*3*4 + 53144*3*4*4
        long expected = 607153 + 231697L * 3 * 4 + 53144L * 3 * 4 * 4;
        assertEquals(expected, f.apply(2, 3, 4));
    }

    @Test
    void expModCostBaseEqualsMod() {
        // base == mod: no penalty
        var f = new CostFunction.ExpModCost(1000, 100, 10);
        long expected = 1000 + 100L * 3 * 5 + 10L * 3 * 5 * 5;
        assertEquals(expected, f.apply(5, 3, 5));
    }

    @Test
    void expModCostBaseGreaterThanMod() {
        // base > mod: 50% penalty (cost0 + cost0/2)
        var f = new CostFunction.ExpModCost(1000, 100, 10);
        // base=10, exp=3, mod=5
        long cost0 = 1000 + 100L * 3 * 5 + 10L * 3 * 5 * 5;
        long expected = cost0 + cost0 / 2;
        assertEquals(expected, f.apply(10, 3, 5));
    }

    @Test
    void expModCostPenaltyUsesIntegerDivision() {
        // Verify integer division for odd cost0
        var f = new CostFunction.ExpModCost(1001, 0, 0);
        // base=5, exp=1, mod=1: cost0 = 1001
        // penalty: 1001 + 1001/2 = 1001 + 500 = 1501
        assertEquals(1501, f.apply(5, 1, 1));
        // base=1, exp=1, mod=1: no penalty, cost0 = 1001
        assertEquals(1001, f.apply(1, 1, 1));
    }
}
