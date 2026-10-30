package spike;

import java.math.BigInteger;

/** Floor image for size deltas: prints a BigInteger sum, no javac. */
public final class Hello {
    public static void main(String[] args) {
        BigInteger k = new BigInteger(args.length > 0 ? args[0] : "41");
        System.out.println("hello " + k.add(BigInteger.ONE));
    }
}
