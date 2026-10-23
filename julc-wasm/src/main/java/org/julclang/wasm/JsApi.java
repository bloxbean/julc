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

    /** Installs {@code registry} as {@code globalThis[engine.global()]} and then calls {@code onJulcReady}. */
    public static void install(ApiRegistry registry, EngineInfo engine) {
        try (var runtime = JsApi.class.getResourceAsStream("/julc-runtime.js")) {
            if (runtime == null) throw new IllegalStateException("Missing JavaScript runtime");
            install(argv -> JSString.of(registry.invoke(((JSString) argv.get(0)).asString(),
                            ((JSString) argv.get(1)).asString())),
                    JSString.of(registry.schemas()), JSString.of(engine.global()), JSString.of(engine.variant()),
                    JSString.of(engine.version()), JSString.of(JsMarshalling.JSON.writeValueAsString(engine.groups())),
                    JSString.of(new String(runtime.readAllBytes(), StandardCharsets.UTF_8)));
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

    @JS(args = {"fn", "schema", "name", "variant", "version", "groups", "source"}, value = """
            (0, eval)(String(source));
            const api = globalThis.JulcRuntime.install(
                (method, body) => String(fn([method, body])), JSON.parse(String(schema)),
                String(variant), String(version), JSON.parse(String(groups)));
            globalThis[String(name)] = api;
            if (typeof globalThis.onJulcReady === 'function') {
                setTimeout(() => globalThis.onJulcReady(api), 0);
            }
            """)
    private static native void install(Function<JSObject, JSString> fn, JSString schema, JSString name,
                                       JSString variant, JSString version, JSString groups, JSString source);
}
