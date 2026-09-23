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

    public static void install(ApiRegistry registry, String variant) {
        try (var runtime = JsApi.class.getResourceAsStream("/julc-runtime.js")) {
            if (runtime == null) throw new IllegalStateException("Missing JavaScript runtime");
            var version = new Properties();
            try (var resource = JsApi.class.getResourceAsStream("/julc-wasm-version.properties")) {
                if (resource != null) version.load(resource);
            }
            install(argv -> JSString.of(registry.invoke(((JSString) argv.get(0)).asString(),
                            ((JSString) argv.get(1)).asString())),
                    JSString.of(registry.schemas()), JSString.of(variant),
                    JSString.of(version.getProperty("version", "dev")),
                    JSString.of(new String(runtime.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load JavaScript API", e);
        }
    }

    @JS(args = {"fn", "schema", "variant", "version", "source"}, value = """
            (0, eval)(String(source));
            globalThis.julc = globalThis.JulcRuntime.install(
                (method, body) => String(fn([method, body])), JSON.parse(String(schema)),
                String(variant), String(version));
            if (typeof globalThis.onJulcReady === 'function') {
                setTimeout(() => globalThis.onJulcReady(globalThis.julc), 0);
            }
            """)
    private static native void install(Function<JSObject, JSString> fn, JSString schema, JSString variant,
                                       JSString version, JSString source);
}
