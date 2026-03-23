package com.bloxbean.julc.playground;

import com.bloxbean.cardano.julc.jrl.JrlCompiler;
import com.bloxbean.julc.playground.api.*;
import com.bloxbean.julc.playground.sandbox.CompilationSandbox;
import com.bloxbean.julc.playground.sandbox.RateLimiter;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JRL Playground HTTP server.
 * <p>
 * Provides a web-based editor for writing, compiling, and testing JRL contracts
 * without requiring Java/Gradle installation.
 */
public class JrlPlaygroundServer {

    private static final int DEFAULT_PORT = 8085;
    private static final int DEFAULT_MAX_THREADS = 4;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long MAX_REQUEST_SIZE = 64 * 1024; // 64KB

    public static void main(String[] args) {
        int port = intEnv("JRL_PLAYGROUND_PORT", DEFAULT_PORT);
        int maxThreads = intEnv("JRL_MAX_COMPILE_THREADS", DEFAULT_MAX_THREADS);
        long timeout = longEnv("JRL_COMPILE_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS);

        var app = createApp(port, maxThreads, timeout);
        printBanner(port);
    }

    public static Javalin createApp(int port, int maxThreads, long timeoutSeconds) {
        var compiler = new JrlCompiler();
        var sandbox = new CompilationSandbox(maxThreads, timeoutSeconds);

        var checkLimiter = new RateLimiter(300, 60_000);
        var compileLimiter = new RateLimiter(60, 60_000);

        var checkController = new CheckController(compiler);
        var transpileController = new TranspileController(compiler);
        var compileController = new CompileController(compiler, sandbox);
        var evaluateController = new EvaluateController(compiler, sandbox);
        var examplesController = new ExamplesController();
        var scenariosController = new ScenariosController();

        var app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.maxRequestSize = MAX_REQUEST_SIZE;
            config.useVirtualThreads = true;
            if (!isNativeImage() && JrlPlaygroundServer.class.getResource("/static/index.html") != null) {
                config.staticFiles.add("/static", Location.CLASSPATH);
            }

            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    rule.anyHost();
                });
            });
        });

        // Rate limiting for heavy endpoints
        app.before("/api/compile", compileLimiter.middleware());
        app.before("/api/evaluate", compileLimiter.middleware());
        app.before("/api/check", checkLimiter.middleware());
        app.before("/api/transpile", checkLimiter.middleware());

        // API routes
        app.post("/api/check", checkController::handle);
        app.post("/api/transpile", transpileController::handle);
        app.post("/api/compile", compileController::handle);
        app.post("/api/evaluate", evaluateController::handle);
        app.get("/api/examples", examplesController::list);
        app.get("/api/examples/{name}", examplesController::get);
        app.get("/api/scenarios/{purpose}", scenariosController::handle);

        // Health check
        app.get("/api/health", ctx -> ctx.json(java.util.Map.of("status", "ok")));

        // In native image, serve static files via classpath resource reads
        // (Jetty's resource-base directory browsing doesn't work in native image)
        if (isNativeImage()) {
            app.get("/{path}", ctx -> {
                String path = ctx.pathParam("path");
                serveClasspathResource(ctx, "/static/" + path);
            });
            app.get("/assets/{path}", ctx -> {
                String path = ctx.pathParam("path");
                serveClasspathResource(ctx, "/static/assets/" + path);
            });
            app.get("/", ctx -> serveClasspathResource(ctx, "/static/index.html"));
        }

        // Cleanup scheduler for rate limiter
        var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "rate-limiter-cleanup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            checkLimiter.cleanup();
            compileLimiter.cleanup();
        }, 5, 5, TimeUnit.MINUTES);

        app.events(event -> event.serverStopping(() -> {
            sandbox.shutdown();
            scheduler.shutdownNow();
        }));

        app.start(port);
        return app;
    }

    private static void printBanner(int port) {
        System.out.println("""
                     _       _     ____   ____  _                                             _\s
                    | |_   _| |   / ___| |  _ \\| | __ _ _   _  __ _ _ __ ___  _   _ _ __   __| |
                 _  | | | | | |  | |     | |_) | |/ _` | | | |/ _` | '__/ _ \\| | | | '_ \\ / _` |
                | |_| | |_| | |__| |___  |  __/| | (_| | |_| | (_| | | | (_) | |_| | | | | (_| |
                 \\___/ \\__,_|____\\____| |_|   |_|\\__,_|\\__, |\\__, |_|  \\___/ \\__,_|_| |_|\\__,_|
                                                         |___/ |___/\s
                """ + "        http://localhost:" + port + "\n");
    }

    private static int intEnv(String name, int defaultValue) {
        String val = System.getenv(name);
        return val != null ? Integer.parseInt(val) : defaultValue;
    }

    private static long longEnv(String name, long defaultValue) {
        String val = System.getenv(name);
        return val != null ? Long.parseLong(val) : defaultValue;
    }

    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html",
            "js", "application/javascript",
            "css", "text/css",
            "json", "application/json",
            "ttf", "font/ttf",
            "woff", "font/woff",
            "woff2", "font/woff2",
            "svg", "image/svg+xml",
            "png", "image/png"
    );

    private static void serveClasspathResource(io.javalin.http.Context ctx, String resourcePath) throws IOException {
        try (var is = JrlPlaygroundServer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                ctx.status(404).result("Not found");
                return;
            }
            String ext = resourcePath.substring(resourcePath.lastIndexOf('.') + 1);
            ctx.contentType(CONTENT_TYPES.getOrDefault(ext, "application/octet-stream"));
            ctx.result(is.readAllBytes());
        }
    }
}
