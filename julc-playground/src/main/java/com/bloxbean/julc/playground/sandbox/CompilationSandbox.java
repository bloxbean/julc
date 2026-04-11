package com.bloxbean.julc.playground.sandbox;

import java.util.concurrent.*;

/**
 * Sandboxed compilation with timeout and concurrency limits.
 */
public class CompilationSandbox {

    private final ExecutorService executor;
    private final Semaphore semaphore;
    private final long timeoutSeconds;

    public CompilationSandbox(int maxConcurrent, long timeoutSeconds) {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.semaphore = new Semaphore(maxConcurrent);
        this.timeoutSeconds = timeoutSeconds;
    }

    public <T> T run(Callable<T> task) throws CompilationTimeoutException, SandboxFullException {
        if (!semaphore.tryAcquire()) {
            throw new SandboxFullException();
        }
        try {
            Future<T> future = executor.submit(task);
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new CompilationTimeoutException();
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Compilation interrupted", e);
        } finally {
            semaphore.release();
        }
    }

    public int availableSlots() {
        return semaphore.availablePermits();
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public static class CompilationTimeoutException extends Exception {
        public CompilationTimeoutException() {
            super("Compilation timed out");
        }
    }

    public static class SandboxFullException extends Exception {
        public SandboxFullException() {
            super("Maximum concurrent compilations reached");
        }
    }
}
