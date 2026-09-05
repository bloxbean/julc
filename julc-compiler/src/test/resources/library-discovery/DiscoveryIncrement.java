package example.cleanup;

import java.math.BigInteger;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

@OnchainLibrary
public class DiscoveryIncrement {
    public static BigInteger increment(BigInteger x) {
        return x.add(BigInteger.ONE);
    }
}
