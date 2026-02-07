package com.bloxbean.cardano.plutus.vm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExBudgetTest {

    @Test
    void zero() {
        assertEquals(0, ExBudget.ZERO.cpuSteps());
        assertEquals(0, ExBudget.ZERO.memoryUnits());
    }

    @Test
    void add() {
        var a = new ExBudget(100, 200);
        var b = new ExBudget(50, 75);
        var sum = a.add(b);
        assertEquals(150, sum.cpuSteps());
        assertEquals(275, sum.memoryUnits());
    }

    @Test
    void addSaturatesPositiveOverflow() {
        var a = new ExBudget(Long.MAX_VALUE, Long.MAX_VALUE);
        var b = new ExBudget(1, 1);
        var sum = a.add(b);
        assertEquals(Long.MAX_VALUE, sum.cpuSteps());
        assertEquals(Long.MAX_VALUE, sum.memoryUnits());
    }

    @Test
    void addSaturatesNegativeUnderflow() {
        var a = new ExBudget(Long.MIN_VALUE, Long.MIN_VALUE);
        var b = new ExBudget(-1, -1);
        var sum = a.add(b);
        assertEquals(Long.MIN_VALUE, sum.cpuSteps());
        assertEquals(Long.MIN_VALUE, sum.memoryUnits());
    }

    @Test
    void isExhaustedFalseForPositive() {
        assertFalse(new ExBudget(100, 200).isExhausted());
        assertFalse(new ExBudget(0, 0).isExhausted());
    }

    @Test
    void isExhaustedTrueForNegative() {
        assertTrue(new ExBudget(-1, 200).isExhausted());
        assertTrue(new ExBudget(100, -1).isExhausted());
    }

    @Test
    void equalsAndHashCode() {
        var a = new ExBudget(100, 200);
        var b = new ExBudget(100, 200);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEquals() {
        assertNotEquals(new ExBudget(100, 200), new ExBudget(100, 201));
    }

    @Test
    void toStringContainsValues() {
        var budget = new ExBudget(42, 99);
        assertTrue(budget.toString().contains("42"));
        assertTrue(budget.toString().contains("99"));
    }
}
