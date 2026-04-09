package com.bloxbean.julc.playground.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class CompilationSandboxTest {

    private CompilationSandbox sandbox;

    @AfterEach
    void tearDown() {
        if (sandbox != null) sandbox.shutdown();
    }

    @Test
    void runsTaskSuccessfully() throws Exception {
        sandbox = new CompilationSandbox(2, 10);
        String result = sandbox.run(() -> "hello");
        assertEquals("hello", result);
    }

    @Test
    void throwsTimeoutOnSlowTask() {
        sandbox = new CompilationSandbox(2, 1); // 1 second timeout
        assertThrows(CompilationSandbox.CompilationTimeoutException.class, () ->
                sandbox.run(() -> {
                    Thread.sleep(5000);
                    return "too slow";
                })
        );
    }

    @Test
    void throwsSandboxFullWhenAllSlotsOccupied() throws Exception {
        sandbox = new CompilationSandbox(1, 10); // 1 concurrent slot

        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);

        // Fill the single slot with a blocking task
        Thread blocker = Thread.ofVirtual().start(() -> {
            try {
                sandbox.run(() -> {
                    started.countDown();
                    finish.await();
                    return "done";
                });
            } catch (Exception ignored) {}
        });

        // Wait for blocker to acquire the slot
        started.await();

        // Now the sandbox should be full
        assertThrows(CompilationSandbox.SandboxFullException.class, () ->
                sandbox.run(() -> "should fail")
        );

        // Release the blocker
        finish.countDown();
        blocker.join(5000);
    }

    @Test
    void availableSlots_reflectsConcurrency() throws Exception {
        sandbox = new CompilationSandbox(3, 10);
        assertEquals(3, sandbox.availableSlots());

        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);

        Thread task = Thread.ofVirtual().start(() -> {
            try {
                sandbox.run(() -> {
                    started.countDown();
                    finish.await();
                    return "done";
                });
            } catch (Exception ignored) {}
        });

        started.await();
        assertEquals(2, sandbox.availableSlots());

        finish.countDown();
        task.join(5000);
        // Slot should be released
        assertEquals(3, sandbox.availableSlots());
    }

    @Test
    void propagatesRuntimeExceptions() {
        sandbox = new CompilationSandbox(2, 10);
        assertThrows(IllegalStateException.class, () ->
                sandbox.run(() -> { throw new IllegalStateException("boom"); })
        );
    }
}
