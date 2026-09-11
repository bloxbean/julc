package example.cleanup;

import java.math.BigInteger;
import org.julclang.stdlib.annotation.OnchainLibrary;

@OnchainLibrary
public class DiscoveryWrapper {
    public static BigInteger twice(BigInteger x) {
        return DiscoveryIncrement.increment(DiscoveryIncrement.increment(x));
    }
}
