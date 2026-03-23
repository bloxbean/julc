package com.bloxbean.julc.playground.sandbox;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple per-IP rate limiter using a sliding window.
 */
public class RateLimiter {

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final long windowMillis;

    public RateLimiter(int maxRequestsPerWindow, long windowMillis) {
        this.maxRequests = maxRequestsPerWindow;
        this.windowMillis = windowMillis;
    }

    public boolean tryAcquire(String ip) {
        long now = System.currentTimeMillis();
        var counter = counters.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.windowStart > windowMillis) {
                return new WindowCounter(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return counter.count.get() <= maxRequests;
    }

    public Handler middleware() {
        return ctx -> {
            if (!tryAcquire(clientIp(ctx))) {
                ctx.status(429).json(java.util.Map.of("error", "Rate limit exceeded. Please slow down."));
            }
        };
    }

    /**
     * Periodically clean up expired entries.
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        counters.entrySet().removeIf(e -> now - e.getValue().windowStart > windowMillis * 2);
    }

    private String clientIp(Context ctx) {
        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return ctx.ip();
    }

    private record WindowCounter(long windowStart, AtomicInteger count) {}
}
