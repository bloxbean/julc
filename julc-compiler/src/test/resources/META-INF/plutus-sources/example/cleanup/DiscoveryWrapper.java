package example.cleanup;

import java.math.BigInteger;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

@OnchainLibrary
public class DiscoveryWrapper {
    public static BigInteger twice(BigInteger x) {
        return DiscoveryIncrement.increment(DiscoveryIncrement.increment(x));
    }
}
