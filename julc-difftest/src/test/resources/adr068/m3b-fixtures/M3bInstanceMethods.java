package m3b.fixtures;

import java.math.BigInteger;
import java.util.Optional;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcArray;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.core.types.Tuple2;
import org.julclang.core.types.Tuple3;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.NewType;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 M3b: instance dispatch by the owner javac bound, and chained reads. */
@SpendingValidator
class M3bInstanceMethods {
    record Datum(BigInteger amount, byte[] owner, PubKeyHash signer, JulcList<BigInteger> values, Inner inner) { }
    record Inner(BigInteger deep, Datum2 next) { }
    record Datum2(boolean flag, String text) { }
    @NewType record Amount(BigInteger value) { }
    @NewType record Tag(byte[] value) { }
    record Wrapped(Amount amount, Tag tag) { }
    record Holder(TokenName token) { }

    static BigInteger add(BigInteger a, BigInteger b) { return a.add(b); }
    static BigInteger sub(BigInteger a, BigInteger b) { return a.subtract(b); }
    static BigInteger mul(BigInteger a, BigInteger b) { return a.multiply(b); }
    static BigInteger div(BigInteger a, BigInteger b) { return a.divide(b); }
    static BigInteger rem(BigInteger a, BigInteger b) { return a.remainder(b); }
    static BigInteger mod(BigInteger a, BigInteger b) { return a.mod(b); }
    static BigInteger abs(BigInteger a) { return a.abs(); }
    static BigInteger negate(BigInteger a) { return a.negate(); }
    static BigInteger max(BigInteger a, BigInteger b) { return a.max(b); }
    static BigInteger min(BigInteger a, BigInteger b) { return a.min(b); }
    static int signum(BigInteger a) { return a.signum(); }
    static int intValue(BigInteger a) { return a.intValue(); }
    static long longValue(BigInteger a) { return a.longValue(); }
    static BigInteger chained(BigInteger a, BigInteger b) { return a.add(b).multiply(b).subtract(a).abs(); }
    static BigInteger fromInt(int a) { return BigInteger.valueOf(a).add(BigInteger.ONE); }
    static boolean bigEquals(BigInteger a, BigInteger b) { return a.equals(b); }

    static boolean stringEquals(String a, String b) { return a.equals(b); }
    static int stringLength(String a) { return a.length(); }
    static byte[] stringBytes(String a) { return a.getBytes(); }

    static boolean dataEquals(PlutusData a, PlutusData b) { return a.equals(b); }
    static boolean recordEquals(Inner a, Inner b) { return a.equals(b); }
    static boolean hashEquals(PubKeyHash a, PubKeyHash b) { return a.equals(b); }

    static long listSize(JulcList<BigInteger> l) { return l.size(); }
    static boolean listEmpty(JulcList<BigInteger> l) { return l.isEmpty(); }
    static BigInteger listHead(JulcList<BigInteger> l) { return l.head(); }
    static JulcList<BigInteger> listTail(JulcList<BigInteger> l) { return l.tail(); }
    static BigInteger listGet(JulcList<BigInteger> l, long i) { return l.get(i); }
    static boolean listContains(JulcList<BigInteger> l, BigInteger x) { return l.contains(x); }
    static boolean bytesListContains(JulcList<byte[]> l, byte[] x) { return l.contains(x); }
    static boolean dataListContains(JulcList<PlutusData> l, PlutusData x) { return l.contains(x); }
    static JulcList<BigInteger> listPrepend(JulcList<BigInteger> l, BigInteger x) { return l.prepend(x); }
    static JulcList<BigInteger> listReverse(JulcList<BigInteger> l) { return l.reverse(); }
    static JulcList<BigInteger> listConcat(JulcList<BigInteger> a, JulcList<BigInteger> b) { return a.concat(b); }
    static JulcList<BigInteger> listTake(JulcList<BigInteger> l, long n) { return l.take(n); }
    static JulcList<BigInteger> listDrop(JulcList<BigInteger> l, long n) { return l.drop(n); }
    static JulcArray<BigInteger> listToArray(JulcList<BigInteger> l) { return l.toArray(); }
    static PlutusData listToData(JulcList<BigInteger> l) { return l.toPlutusData(); }
    static BigInteger chainedList(JulcList<BigInteger> l) { return l.tail().tail().head().add(l.head()); }

    static BigInteger arrayGet(JulcArray<BigInteger> a, long i) { return a.get(i); }
    static long arrayLength(JulcArray<BigInteger> a) { return a.length(); }

    static long mapSize(JulcMap<byte[], BigInteger> m) { return m.size(); }
    static boolean mapEmpty(JulcMap<byte[], BigInteger> m) { return m.isEmpty(); }
    static BigInteger mapGet(JulcMap<byte[], BigInteger> m, byte[] k) { return m.get(k); }
    static boolean mapContains(JulcMap<byte[], BigInteger> m, byte[] k) { return m.containsKey(k); }
    static Optional<BigInteger> mapLookup(JulcMap<byte[], BigInteger> m, byte[] k) { return m.lookup(k); }
    static JulcMap<byte[], BigInteger> mapInsert(JulcMap<byte[], BigInteger> m, byte[] k, BigInteger v) { return m.insert(k, v); }
    static JulcMap<byte[], BigInteger> mapDelete(JulcMap<byte[], BigInteger> m, byte[] k) { return m.delete(k); }
    static JulcList<byte[]> mapKeys(JulcMap<byte[], BigInteger> m) { return m.keys(); }
    static JulcList<BigInteger> mapValues(JulcMap<byte[], BigInteger> m) { return m.values(); }
    static JulcMap<byte[], BigInteger> mapTail(JulcMap<byte[], BigInteger> m) { return m.tail(); }
    static PlutusData mapToData(JulcMap<byte[], BigInteger> m) { return m.toPlutusData(); }

    static boolean optionalPresent(Optional<BigInteger> o) { return o.isPresent(); }
    static boolean optionalEmpty(Optional<BigInteger> o) { return o.isEmpty(); }
    static BigInteger optionalGet(Optional<BigInteger> o) { return o.get(); }

    static BigInteger accessor(Datum d) { return d.amount(); }
    static byte[] bytesAccessor(Datum d) { return d.owner(); }
    static BigInteger deepAccessor(Datum d) { return d.inner().deep(); }
    static boolean deeperAccessor(Datum d) { return d.inner().next().flag(); }
    static String stringAccessor(Datum d) { return d.inner().next().text(); }
    static BigInteger accessorThenMethod(Datum d) { return d.values().head().add(d.amount()); }
    static byte[] hashOfComponent(Datum d) { return d.signer().hash(); }
    static byte[] nameOfToken(Holder h) { return h.token().name(); }
    static BigInteger newTypeValue(Wrapped w) { return w.amount().value(); }
    static byte[] newTypeBytes(Wrapped w) { return w.tag().value(); }
    static BigInteger tupleFirst(Tuple2<BigInteger, byte[]> t) { return t.first(); }
    static byte[] tupleSecond(Tuple2<BigInteger, byte[]> t) { return t.second(); }
    static BigInteger tupleThird(Tuple3<byte[], byte[], BigInteger> t) { return t.third(); }

    static BigInteger fee(ScriptContext ctx) { return ctx.txInfo().fee(); }
    static long inputs(TxInfo info) { return info.inputs().size(); }
    static JulcList<PubKeyHash> signatories(ScriptContext ctx) { return ctx.txInfo().signatories(); }
    static boolean signed(ScriptContext ctx, PubKeyHash who) { return ctx.txInfo().signatories().contains(who); }
    static Address outAddress(TxOut out) { return out.address(); }
    static Credential outCredential(TxOut out) { return out.address().credential(); }
    static BigInteger lovelace(TxOut out) { return out.value().lovelaceOf(); }
    static boolean noValue(Value v) { return v.isEmpty(); }
    static BigInteger lovelaceOf(Value v) { return v.lovelaceOf(); }
    static boolean containsPolicy(Value v, PolicyId p) { return v.containsPolicy(p); }
    static BigInteger assetOf(Value v, PolicyId p, TokenName t) { return v.assetOf(p, t); }
    static PlutusData hashToData(PubKeyHash p) { return p.toPlutusData(); }
    static boolean policyEquals(PolicyId a, PolicyId b) { return a.equals(b); }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return ctx.txInfo().signatories().isEmpty();
    }
}
