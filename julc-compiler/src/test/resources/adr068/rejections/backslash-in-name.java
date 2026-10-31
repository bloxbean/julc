// entry: method m
// expect-message: Illegal character '\\'
import java.math.BigInteger;
public class P {
    static final BigInteger a = BigInteger.ONE;
    public static BigInteger m(BigInteger x) {
        BigInteger \u005cu0061 = x;
        return a;
    }
}
