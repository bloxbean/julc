package spike;

import org.julclang.cli.JulcMain;

import java.util.Arrays;

/** Size-delta image: the julc CLI plus the javac probe, both reachable. "javac ..." runs the probe. */
public final class CombinedCliMain {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("javac")) {
            JavacProbe.main(Arrays.copyOfRange(args, 1, args.length));
        } else {
            JulcMain.main(args);
        }
    }
}
