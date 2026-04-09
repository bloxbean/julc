package com.bloxbean.julc.playground.sandbox;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple per-IP rate limiter using a sliding window.
 */
public class RateLimiter {

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final long windowMillis;
    private final boolean trustProxy;

    public RateLimiter(int maxRequestsPerWindow, long windowMillis) {
        this(maxRequestsPerWindow, windowMillis, false);
    }

    /**
     * @param trustProxy if true, trusts X-Forwarded-For from loopback/private IPs (behind reverse proxy)
     */
    public RateLimiter(int maxRequestsPerWindow, long windowMillis, boolean trustProxy) {
        this.maxRequests = maxRequestsPerWindow;
        this.windowMillis = windowMillis;
        this.trustProxy = trustProxy;
    }

    public boolean tryAcquire(String ip) {
        long now = System.currentTimeMillis();
        var counter = counters.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.windowStart >= windowMillis) {
                return new WindowCounter(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        // Read the count atomically — the value from incrementAndGet inside compute
        // may already be stale, but get() gives us the current (possibly higher) count,
        // which is a safe over-estimate (never under-counts)
        return counter.count.get() <= maxRequests;
    }

    /**
     * Returns the current request count for the given IP, or 0 if not tracked.
     */
    public int currentCount(String ip) {
        var counter = counters.get(ip);
        return counter != null ? counter.count.get() : 0;
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

    String clientIp(Context ctx) {
        if (trustProxy && isTrustedProxy(ctx.ip())) {
            String forwarded = ctx.header("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return ctx.ip();
    }

    private static boolean isTrustedProxy(String ip) {
        if (LOOPBACK.contains(ip) || ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        // RFC 1918: 172.16.0.0/12 covers 172.16.x.x through 172.31.x.x
        if (ip.startsWith("172.")) {
            int dot = ip.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(ip.substring(4, dot));
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    private record WindowCounter(long windowStart, AtomicInteger count) {}
}
