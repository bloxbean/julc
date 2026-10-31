// entry: method m
// expect-message: @NewType constructor must have exactly 1 argument
import java.math.BigInteger;
class F {
    @NewType record Price(BigInteger value) {}
    static Price m(BigInteger a) { return new Price(a, a); }
}
