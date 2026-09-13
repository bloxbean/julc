package example.cleanup;

import java.math.BigInteger;
import org.julclang.stdlib.annotation.OnchainLibrary;

@OnchainLibrary
public class DiscoveryIncrement {
    public static BigInteger increment(BigInteger x) {
        return x.add(BigInteger.ONE);
    }
}
