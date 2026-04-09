package com.bloxbean.julc.playground.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void allowsRequestsWithinLimit() {
        var limiter = new RateLimiter(3, 60_000);
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertTrue(limiter.tryAcquire("1.2.3.4"));
    }

    @Test
    void blocksRequestsExceedingLimit() {
        var limiter = new RateLimiter(2, 60_000);
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertFalse(limiter.tryAcquire("1.2.3.4"));
    }

    @Test
    void tracksIpsIndependently() {
        var limiter = new RateLimiter(1, 60_000);
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertFalse(limiter.tryAcquire("1.2.3.4"));
        // Different IP should still be allowed
        assertTrue(limiter.tryAcquire("5.6.7.8"));
    }

    @Test
    void windowResetsAfterExpiry() throws InterruptedException {
        var limiter = new RateLimiter(1, 50); // 50ms window
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertFalse(limiter.tryAcquire("1.2.3.4"));

        Thread.sleep(60); // Wait for window to expire
        assertTrue(limiter.tryAcquire("1.2.3.4"));
    }

    @Test
    void cleanupRemovesExpiredEntries() throws InterruptedException {
        var limiter = new RateLimiter(1, 50);
        limiter.tryAcquire("1.2.3.4");
        limiter.tryAcquire("5.6.7.8");

        Thread.sleep(120); // Wait for 2x window
        limiter.cleanup();

        // After cleanup + window expiry, should be able to acquire again
        assertTrue(limiter.tryAcquire("1.2.3.4"));
        assertTrue(limiter.tryAcquire("5.6.7.8"));
    }
}
