package org.julclang.playground.wasm;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSBoolean;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;

import java.util.function.Function;

/**
 * GraalVM Web Image entry point of the in-browser playground engine.
 * <p>
 * Installs {@code globalThis.julcPlayground.dispatch(method, path, body)}, which returns the
 * {@link PlaygroundDispatcher} JSON envelope, then calls {@code globalThis.onJulcPlaygroundReady} if defined.
 * {@code @JS.Export} is not available in Web Image 25.3, so the function object is installed by a small
 * {@code @JS} bootstrap snippet.
 */
public final class PlaygroundWasm {

    private PlaygroundWasm() {}

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        PlaygroundDispatcher dispatcher = PlaygroundDispatcher.create();
        install(argv -> {
            JSObject result = JSObject.create();
            try {
                String response = dispatcher.dispatch(string(argv.get(0)), string(argv.get(1)), string(argv.get(2)));
                result.set("ok", JSBoolean.of(true));
                result.set("value", JSString.of(response));
            } catch (Throwable t) {
                result.set("ok", JSBoolean.of(false));
                result.set("error", JSString.of(t.getClass().getName() + ": " + t.getMessage()));
            }
            return result;
        });
        signalReady((int) (System.currentTimeMillis() - start));
    }

    /** JS strings arrive as {@link JSString}; the bootstrap passes {@code null} for a missing body. */
    private static String string(Object value) {
        return value instanceof JSString js ? js.asString() : null;
    }

    // Uses only JSObject.get(Object) and JSString.asString(), which exist in the Web Image API of GraalVM 25.0 and 25.3.
    @JS(args = {"fn"}, value = """
            globalThis.julcPlayground = {
                dispatch(method, path, body) {
                    const r = fn([String(method), String(path), body == null ? null : String(body)]);
                    if (!r.ok) throw new Error(r.error);
                    return r.value;
                }
            };
            """)
    private static native void install(Function<JSObject, JSObject> fn);

    @JS.Coerce
    @JS(args = {"initMs"}, value = """
            globalThis.julcPlayground.ready = true;
            globalThis.julcPlayground.initMs = initMs;
            if (typeof globalThis.onJulcPlaygroundReady === 'function') {
                setTimeout(() => globalThis.onJulcPlaygroundReady(globalThis.julcPlayground), 0);
            }
            """)
    private static native void signalReady(int initMs);
}
