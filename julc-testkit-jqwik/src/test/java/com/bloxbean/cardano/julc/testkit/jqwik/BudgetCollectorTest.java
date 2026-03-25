package com.bloxbean.cardano.julc.testkit.jqwik;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BudgetCollector}.
 */
class BudgetCollectorTest {

    private static EvalResult success(long cpu, long mem) {
        return new EvalResult.Success(new Term.Const(Constant.unit()), new ExBudget(cpu, mem), List.of());
    }

    @Test
    void emptyCollectorHasZeroCount() {
        var collector = new BudgetCollector();
        assertEquals(0, collector.count());
        assertEquals("Budget: no trials recorded", collector.summary());
    }

    @Test
    void singleRecordStats() {
        var collector = new BudgetCollector();
        collector.record(success(1000, 500));

        assertEquals(1, collector.count());
        assertEquals(1000, collector.maxCpu());
        assertEquals(500, collector.maxMem());
        assertEquals(1000, collector.avgCpu());
        assertEquals(500, collector.avgMem());
        assertEquals(1000, collector.p99Cpu());
        assertEquals(500, collector.p99Mem());
        assertEquals(1000, collector.minCpu());
        assertEquals(500, collector.minMem());
    }

    @Test
    void multipleRecordStats() {
        var collector = new BudgetCollector();
        collector.record(success(100, 50));
        collector.record(success(200, 100));
        collector.record(success(300, 150));
        collector.record(success(400, 200));

        assertEquals(4, collector.count());
        assertEquals(400, collector.maxCpu());
        assertEquals(200, collector.maxMem());
        assertEquals(250, collector.avgCpu());  // (100+200+300+400)/4
        assertEquals(125, collector.avgMem());  // (50+100+150+200)/4
        assertEquals(100, collector.minCpu());
        assertEquals(50, collector.minMem());
    }

    @Test
    void p99ComputationAccuracy() {
        var collector = new BudgetCollector();
        // Add 100 values: 1, 2, 3, ..., 100
        for (int i = 1; i <= 100; i++) {
            collector.record(success(i * 1000, i * 100));
        }

        assertEquals(100, collector.count());
        assertEquals(100_000, collector.maxCpu());
        assertEquals(10_000, collector.maxMem());
        // p99 of 1..100 -> index ceil(99/100 * 100) - 1 = 98 -> value 99_000
        assertEquals(99_000, collector.p99Cpu());
        assertEquals(9_900, collector.p99Mem());
    }

    @Test
    void summaryFormatsNicely() {
        var collector = new BudgetCollector();
        collector.record(success(2_500_000, 8_500));
        collector.record(success(3_100_000, 12_000));

        String summary = collector.summary();
        assertTrue(summary.contains("2 trials"));
        assertTrue(summary.contains("CPU:"));
        assertTrue(summary.contains("Mem:"));
    }

    @Test
    void handlesFailureResults() {
        var collector = new BudgetCollector();
        var failure = new EvalResult.Failure("error", new ExBudget(5000, 2000), List.of());
        collector.record(failure);

        assertEquals(1, collector.count());
        assertEquals(5000, collector.maxCpu());
        assertEquals(2000, collector.maxMem());
    }

    @Test
    void handlesBudgetExhausted() {
        var collector = new BudgetCollector();
        var exhausted = new EvalResult.BudgetExhausted(new ExBudget(9999, 4444), List.of());
        collector.record(exhausted);

        assertEquals(1, collector.count());
        assertEquals(9999, collector.maxCpu());
        assertEquals(4444, collector.maxMem());
    }

    @Test
    void threadSafeConcurrentRecording() throws InterruptedException {
        var collector = new BudgetCollector();
        int numThreads = 10;
        int recordsPerThread = 100;
        var latch = new CountDownLatch(numThreads);

        try (ExecutorService executor = Executors.newFixedThreadPool(numThreads)) {
            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < recordsPerThread; i++) {
                            collector.record(success(
                                    threadId * 1000L + i,
                                    threadId * 100L + i));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }

        assertEquals(numThreads * recordsPerThread, collector.count());
    }

    @Test
    void formatNumberScaling() {
        var collector = new BudgetCollector();
        // Test with large values to verify formatting
        collector.record(success(1_500_000_000L, 250_000));

        String summary = collector.summary();
        assertTrue(summary.contains("1.5B") || summary.contains("1500.0M"),
                "Should format billions: " + summary);
        assertTrue(summary.contains("250.0K"),
                "Should format thousands: " + summary);
    }

    @Test
    void p99WithSmallPopulation() {
        var collector = new BudgetCollector();
        collector.record(success(100, 10));
        collector.record(success(200, 20));

        // With 2 values, p99 index = ceil(99/100 * 2) - 1 = ceil(1.98) - 1 = 1
        assertEquals(200, collector.p99Cpu());
        assertEquals(20, collector.p99Mem());
    }

    @Test
    void p99With10Values() {
        var collector = new BudgetCollector();
        for (int i = 1; i <= 10; i++) {
            collector.record(success(i * 100, i * 10));
        }

        // p99 of 10 values: index = ceil(99/100 * 10) - 1 = ceil(9.9) - 1 = 9
        assertEquals(1000, collector.p99Cpu());
        assertEquals(100, collector.p99Mem());
    }

    @Test
    void summaryContainsAllStatistics() {
        var collector = new BudgetCollector();
        collector.record(success(2_500_000, 8_500));
        collector.record(success(3_100_000, 12_000));

        String summary = collector.summary();

        // Verify all stat labels present
        assertTrue(summary.contains("avg="), "Missing avg: " + summary);
        assertTrue(summary.contains("p99="), "Missing p99: " + summary);
        assertTrue(summary.contains("max="), "Missing max: " + summary);
        assertTrue(summary.contains("min="), "Missing min: " + summary);

        // Verify avg CPU is formatted correctly (2_800_000 avg -> 2.8M)
        assertTrue(summary.contains("2.8M"), "avg CPU should be 2.8M: " + summary);
        // Verify max CPU (3_100_000 -> 3.1M)
        assertTrue(summary.contains("3.1M"), "max CPU should be 3.1M: " + summary);
        // Verify min CPU (2_500_000 -> 2.5M)
        assertTrue(summary.contains("2.5M"), "min CPU should be 2.5M: " + summary);
    }

    @Test
    void formatNumberMillionsRange() {
        var collector = new BudgetCollector();
        collector.record(success(5_500_000, 1_200_000));

        String summary = collector.summary();
        assertTrue(summary.contains("5.5M"), "Should format millions for CPU: " + summary);
        assertTrue(summary.contains("1.2M"), "Should format millions for Mem: " + summary);
    }
}
