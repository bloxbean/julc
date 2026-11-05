package p;
import java.math.BigInteger;
import java.util.function.*;
import org.julclang.stdlib.lib.ByteStringLib;
import org.julclang.core.PlutusData;
import org.julclang.core.PlutusData.ConstrData;
class Lint3 {
    static Function<BigInteger, BigInteger> FIELD = x -> x;
    @org.julclang.stdlib.annotation.Param static PlutusData.BytesData banned;
    static BigInteger f(Object o, JulcList<BigInteger> list, BigInteger amount) {
        var g = (Function<BigInteger, BigInteger>) x -> x; g.apply(BigInteger.ONE);
        Function<BigInteger, BigInteger> h = x -> x; h /* c */ .apply(BigInteger.ONE); FIELD.apply(null);
        var z = ByteStringLib /* c */ .zeros(3);
        var w = org.julclang.stdlib.lib.ByteStringLib.empty();
        var s = Optional . mkSome(1); var n = Optional.<BigInteger>mkNone();
        var d = PlutusData /*c*/ .cast(o); var e = new PlutusData . ConstrData(0, null); var e2 = new ConstrData(0, null);
        var m = list.map(x -> x).head(); var m2 = list.map(x -> x).get(0);
        var hh = Crypto.sha2_256(new byte[0]).hash().hash();
        var t = switch (o) { case Tuple2 tt -> 1; case Tuple3<?, ?, ?> t3 -> 2; case PubKeyCredential amount1 -> amount; default -> 0; };
        var u = switch (o) { case Finite amount -> amount; default -> BigInteger.ZERO; };
        for (var i : list) { if (true) return BigInteger.ONE; }
        do { return null; } while (false);
        int q; q = 1;
        return BigInteger.ZERO;
    }
    @org.julclang.stdlib.annotation.Entrypoint
    static boolean validate(BigInteger redeemer, PlutusData ctx) { return true; }
}
