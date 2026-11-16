package org.julclang.wasm;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.function.Function;

/** The only dependency on Web Image's experimental JavaScript interop API. */
public final class JsApi {
    private JsApi() {}

    /**
     * Installs {@code registry} as {@code globalThis[engine.global()]} and then calls {@code onJulcReady}. When the
     * worker loaded {@code julc-runtime.js} before the launcher, the engine uses that file, which must be stamped with
     * this image's JuLC version; otherwise the image evaluates its own copy (ADR-077 D2).
     */
    public static void install(ApiRegistry registry, EngineInfo engine) {
        engine.requireAdvertises(registry);
        try (var runtime = JsApi.class.getResourceAsStream("/julc-runtime.js")) {
            if (runtime == null) throw new IllegalStateException("Missing JavaScript runtime");
            install(argv -> JSString.of(registry.invoke(((JSString) argv.get(0)).asString(),
                            ((JSString) argv.get(1)).asString())),
                    JSString.of(registry.schemas()), JSString.of(engine.global()), JSString.of(engine.variant()),
                    JSString.of(engine.version()), JSString.of(JsMarshalling.JSON.writeValueAsString(engine.groups())),
                    JSString.of(julcVersion()), JSString.of(new String(runtime.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load JavaScript API", e);
        }
    }

    /** The JuLC version this module was built as, or {@code dev} when the build recorded none. */
    public static String julcVersion() {
        var version = new Properties();
        try (var resource = JsApi.class.getResourceAsStream("/julc-wasm-version.properties")) {
            if (resource != null) version.load(resource);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the JuLC version", e);
        }
        return version.getProperty("version", "dev");
    }

    // JULC_RUNTIME_PRELOAD is recorded by the worker (or the Node shim) that loaded julc-runtime.js: that runtime must
    // be this build's, or the engine fails to start, naming both versions. Without a recorded preload, a runtime some
    // other engine left on a shared global is used only when it is stamped with this build; otherwise the image
    // evaluates its own copy, keeping the other engine's global. The Symbol.for('julc.runtime') property tells the
    // worker which runtime the engine uses; a symbol, so no operation group can take its name.
    // uses.
    @JS(args = {"fn", "schema", "name", "variant", "version", "groups", "julcVersion", "source"}, value = """
            const own = String(julcVersion);
            // The worker hears the cause before main fails; detail names the runtime's build when it is the cause.
            const startupError = (message, detail) => {
                globalThis.onJulcStartupError?.(message, detail);
                return new Error(message);
            };
            const found = globalThis.JulcRuntime;
            const stamp = found !== null && typeof found === 'object' && typeof found.install === 'function'
                ? found.julcVersion : undefined;
            const preload = globalThis.JULC_RUNTIME_PRELOAD;
            let runtime = found, origin = 'preloaded';
            if (preload !== undefined) {
                if (preload !== 'loaded' || stamp !== own) {
                    throw startupError('JuLC engine ' + own + ' cannot start: the runtime file julc-runtime.js loaded '
                        + 'before it is ' + (preload !== 'loaded' ? 'missing'
                            : typeof stamp === 'string' ? 'JuLC ' + stamp : 'not a stamped JuLC runtime')
                        + '; serve the julc-runtime.js of JuLC ' + own + ' with this engine',
                        {engineJulcVersion: own, runtimeJulcVersion: typeof stamp === 'string' ? stamp : null});
                }
            } else if (stamp !== own) {
                try {
                    (0, eval)(String(source));
                } catch (error) {
                    throw startupError('JuLC engine ' + own + ' cannot start: the runtime file julc-runtime.js was not '
                        + "loaded, and evaluating the engine's own copy failed (for example under a Content Security "
                        + "Policy without 'unsafe-eval'): " + error);
                }
                runtime = globalThis.JulcRuntime;
                if (found !== undefined) globalThis.JulcRuntime = found;
                origin = 'evaluated';
            } else {
                origin = 'shared';
            }
            if (String(name) in globalThis) throw startupError('Global ' + String(name) + ' is already defined');
            const api = runtime.install(
                (method, body) => String(fn([method, body])), JSON.parse(String(schema)),
                String(variant), String(version), JSON.parse(String(groups)), own);
            Object.defineProperty(api, Symbol.for('julc.runtime'), {value: origin});
            globalThis[String(name)] = api;
            if (typeof globalThis.onJulcReady === 'function') {
                setTimeout(() => globalThis.onJulcReady(api), 0);
            }
            """)
    private static native void install(Function<JSObject, JSString> fn, JSString schema, JSString name,
                                       JSString variant, JSString version, JSString groups, JSString julcVersion,
                                       JSString source);
}
