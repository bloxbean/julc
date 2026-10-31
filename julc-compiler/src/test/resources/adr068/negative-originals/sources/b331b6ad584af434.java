package demo;
import java.math.BigInteger;
import org.julclang.core.types.JulcList;
@OnchainLibrary
public class Seq {
    public record Quote(BigInteger amount, boolean approved) {}
    @NewType public record Price(BigInteger value) {}
    @NewType public record Quantity(BigInteger value) {}
    public static <T> T firstOr(JulcList<T> items, T fallback) {
        if (items.isEmpty()) return fallback;
        return items.head();
    }
    public static <T> BigInteger count(JulcList<T> items) {
        if (items.isEmpty()) return BigInteger.ZERO;
        return count(items.tail()).add(BigInteger.ONE);
    }
    public static <T> boolean contains(JulcList<T> items, T target) {
        if (items.isEmpty()) return false;
        if (items.head().equals(target)) return true;
        return contains(items.tail(), target);
    }
    public static <T> JulcList<T> pair(T first, T second) {
        return JulcList.of(first, second);
    }
    public static BigInteger scaled(BigInteger x) { return x.multiply(BigInteger.TEN); }
    public static <T> BigInteger scaledFirst(JulcList<T> items, BigInteger fallback) {
        if (items.isEmpty()) return scaled(fallback);
        return scaled(BigInteger.ONE);
    }
    public static <T> BigInteger countTwice(JulcList<T> items) {
        return count(items).add(count(items));
    }
    public static <T extends Comparable<T>> T bounded(T value) { return value; }
    public static <T> BigInteger wildcard(JulcList<? extends T> items) { return BigInteger.ZERO; }
}
