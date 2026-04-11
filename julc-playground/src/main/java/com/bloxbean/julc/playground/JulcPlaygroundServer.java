package com.bloxbean.julc.playground;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.julc.playground.api.*;
import com.bloxbean.julc.playground.sandbox.CompilationSandbox;
import com.bloxbean.julc.playground.sandbox.RateLimiter;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JuLC Playground HTTP server.
 * <p>
 * Provides a web-based editor for writing, compiling, and testing Java contracts
 * without requiring Java/Gradle installation.
 */
public class JulcPlaygroundServer {

    private static final Logger log = LoggerFactory.getLogger(JulcPlaygroundServer.class);

    private static final int DEFAULT_PORT = 8085;
    private static final int DEFAULT_MAX_THREADS = 4;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long MAX_REQUEST_SIZE = 256 * 1024; // 256KB (source + library + metadata)

    public static void main(String[] args) {
        int port = intEnv("JULC_PLAYGROUND_PORT", intEnv("JRL_PLAYGROUND_PORT", DEFAULT_PORT));
        int maxThreads = intEnv("JULC_MAX_COMPILE_THREADS", intEnv("JRL_MAX_COMPILE_THREADS", DEFAULT_MAX_THREADS));
        long timeout = longEnv("JULC_COMPILE_TIMEOUT_SECONDS", longEnv("JRL_COMPILE_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS));

        var app = createApp(port, maxThreads, timeout);
        printBanner(port);
    }

    public static Javalin createApp(int port, int maxThreads, long timeoutSeconds) {
        var stdlib = com.bloxbean.cardano.julc.stdlib.StdlibRegistry.defaultRegistry();
        var julcCompiler = new JulcCompiler(stdlib::lookup);
        var sandbox = new CompilationSandbox(maxThreads, timeoutSeconds);

        // Cache stdlib source scan once at startup (classpath doesn't change at runtime)
        var cachedLibSources = com.bloxbean.cardano.julc.compiler.LibrarySourceResolver
                .scanClasspathSources(JulcCompiler.class.getClassLoader());

        boolean behindProxy = "true".equalsIgnoreCase(System.getenv("JULC_BEHIND_PROXY"));
        var checkLimiter = new RateLimiter(300, 60_000, behindProxy);
        var compileLimiter = new RateLimiter(60, 60_000, behindProxy);

        var checkController = new CheckController();
        var compileController = new CompileController(julcCompiler, sandbox, cachedLibSources);
        var evaluateController = new EvaluateController(julcCompiler, sandbox, cachedLibSources);
        var examplesController = new ExamplesController();
        var scenariosController = new ScenariosController();
        var expressionEvalController = new ExpressionEvalController(sandbox);

        String corsOrigins = System.getenv("JULC_CORS_ORIGINS");

        var app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.maxRequestSize = MAX_REQUEST_SIZE;
            config.useVirtualThreads = true;
            if (!isNativeImage() && JulcPlaygroundServer.class.getResource("/static/index.html") != null) {
                config.staticFiles.add("/static", Location.CLASSPATH);
            }

            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    if (corsOrigins != null && !corsOrigins.isBlank() && !"*".equals(corsOrigins)) {
                        for (String origin : corsOrigins.split(",")) {
                            rule.allowHost(origin.trim());
                        }
                    } else {
                        rule.anyHost();
                    }
                });
            });
        });

        // Request logging
        app.before("/api/*", ctx -> ctx.attribute("startTime", System.currentTimeMillis()));
        app.after("/api/*", ctx -> {
            Long start = ctx.attribute("startTime");
            long duration = start != null ? System.currentTimeMillis() - start : -1;
            log.info("{} {} {} {}ms", ctx.method(), ctx.path(), ctx.status(), duration);
        });

        // Rate limiting for heavy endpoints
        app.before("/api/compile", compileLimiter.middleware());
        app.before("/api/evaluate", compileLimiter.middleware());
        app.before("/api/eval", compileLimiter.middleware());
        app.before("/api/check", checkLimiter.middleware());

        // API routes
        app.post("/api/check", checkController::handle);
        app.post("/api/compile", compileController::handle);
        app.post("/api/evaluate", evaluateController::handle);
        app.post("/api/eval", expressionEvalController::handle);
        app.get("/api/examples", examplesController::list);
        app.get("/api/examples/{name}", examplesController::get);
        app.get("/api/scenarios/{purpose}", scenariosController::handle);

        // Health check
        app.get("/api/health", ctx -> ctx.json(Map.of(
                "status", "ok",
                "sandbox", Map.of(
                        "availableSlots", sandbox.availableSlots(),
                        "maxConcurrent", maxThreads
                )
        )));

        // In native image, serve static files via classpath resource reads
        // (Jetty's resource-base directory browsing doesn't work in native image)
        if (isNativeImage()) {
            app.get("/{path}", ctx -> {
                String path = ctx.pathParam("path");
                if (path.contains("..")) { ctx.status(400).result("Invalid path"); return; }
                serveClasspathResource(ctx, "/static/" + path);
            });
            app.get("/assets/{path}", ctx -> {
                String path = ctx.pathParam("path");
                if (path.contains("..")) { ctx.status(400).result("Invalid path"); return; }
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
                     _       _      ____  _                                             _\s
                    | |_   _| | ___|  _ \\| | __ _ _   _  __ _ _ __ ___  _   _ _ __   __| |
                 _  | | | | | |/ __| |_) | |/ _` | | | |/ _` | '__/ _ \\| | | | '_ \\ / _` |
                | |_| | |_| | | (__|  __/| | (_| | |_| | (_| | | | (_) | |_| | | | | (_| |
                 \\___/ \\__,_|_|\\___|_|   |_|\\__,_|\\__, |\\__, |_|  \\___/ \\__,_|_| |_|\\__,_|
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
        try (var is = JulcPlaygroundServer.class.getResourceAsStream(resourcePath)) {
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
