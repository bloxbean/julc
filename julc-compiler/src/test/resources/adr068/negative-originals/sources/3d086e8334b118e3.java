import java.math.BigInteger;

record Datum(BigInteger value) {}

class LibUtils {
    static BigInteger extract(Datum d) { return d.value(); }
}
