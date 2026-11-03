import java.math.BigInteger;
import org.julclang.stdlib.annotation.OnchainLibrary;

@OnchainLibrary
public class B5SelfLibrary {

    static final BigInteger HALF = BigInteger.valueOf(100).divide(BigInteger.TWO);

    public static BigInteger g(BigInteger x) {
        return x.add(x);
    }

    public static BigInteger f(BigInteger x) {
        return B5SelfLibrary.g(x).add(HALF);
    }
}
