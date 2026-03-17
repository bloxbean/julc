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
        var f = new CostFunction.SubtractedSizes(0, 1, 1);
        assertEquals(7, f.apply(10, 3));  // 0 + 1 * (10-3) = 7
        assertEquals(1, f.apply(3, 10));  // max(0, 3-10) = 0 → max(1, 0) = 1
    }

    @Test
    void constAboveDiagonal() {
        var f = new CostFunction.ConstAboveDiagonal(85848, 123203, 7305, -900, 1716, 549, 57, 85848);
        // When x > y, returns constant
        assertEquals(85848, f.apply(10, 5));
        // When x <= y, returns quadratic formula (clamped to minimum)
        long result = f.apply(1, 10);
        assertTrue(result >= 85848, "Result should be at least minimum: " + result);
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
    void expModCost() {
        var f = new CostFunction.ExpModCost(607153, 231697, 53144);
        assertEquals(607153 + 231697 * 2 * 3 + 53144 * 2 * 4, f.apply(2, 3, 4));
    }
}
