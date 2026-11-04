// entry: method m
// expect: JULC0085
// expect-message: constructor Price in record F\.Price cannot be applied to given types
import java.math.BigInteger;
import org.julclang.stdlib.annotation.NewType;
class F {
    @NewType record Price(BigInteger value) {}
    static Price m(BigInteger a) { return new Price(a, a); }
}
