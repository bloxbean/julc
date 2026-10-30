package spike;

import org.julclang.wasm.JulcWasm;

/**
 * Size-delta image: julc-wasm's full engine plus the javac probe, both reachable.
 * "javac ..." runs the probe; anything else starts the julc-wasm full engine as JulcWasm.main does.
 */
public final class CombinedMain {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("javac")) {
            JavacProbe.main(java.util.Arrays.copyOfRange(args, 1, args.length));
        } else {
            JulcWasm.main(args);
        }
    }
}
