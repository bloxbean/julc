package m3b.fixtures;

import java.math.BigInteger;
import java.util.Optional;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcArray;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.Credential;
import org.julclang.ledger.Interval;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.Value;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 M3b: static calls, by the class javac bound. */
@SpendingValidator
class M3bStatics {
    static final BigInteger SEVEN = BigInteger.valueOf(7);
    static final PlutusData SEVEN_AS_DATA = Builtins.iData(SEVEN);

    static long helper(long a, long b) { return a * b; }
    static long noArguments() { return 2; }
    static long callsHelper(long a) { return helper(a, noArguments()) + helper(noArguments(), a); }
    static long recursive(long n) { return n <= 0 ? 0 : n + recursive(n - 1); }
    static boolean even(long n) { return n == 0 || odd(n - 1); }
    static boolean odd(long n) { return n != 0 && even(n - 1); }

    static BigInteger valueOf(long a) { return BigInteger.valueOf(a); }
    static BigInteger valueOfLiteral() { return BigInteger.valueOf(42); }
    static BigInteger valueOfSum(long a) { return BigInteger.valueOf(a + 1); }

    static long abs(long a) { return Math.abs(a); }
    static int absInt(int a) { return Math.abs(a); }
    static long max(long a, long b) { return Math.max(a, b); }
    static long min(long a, long b) { return Math.min(a, b); }
    static long floorDiv(long a, long b) { return Math.floorDiv(a, b); }
    static long floorMod(long a, long b) { return Math.floorMod(a, b); }

    static PlutusData iData(BigInteger a) { return Builtins.iData(a); }
    static PlutusData iDataLong(long a) { return Builtins.iData(a); }
    static PlutusData bData(byte[] a) { return Builtins.bData(a); }
    static BigInteger unIData(PlutusData d) { return Builtins.unIData(d); }
    static byte[] unBData(PlutusData d) { return Builtins.unBData(d); }
    static PlutusData headOfList(PlutusData d) { return Builtins.headList(Builtins.unListData(d)); }
    static boolean nullList(PlutusData d) { return Builtins.nullList(Builtins.tailList(Builtins.unListData(d))); }
    static boolean equalsData(PlutusData a, PlutusData b) { return Builtins.equalsData(a, b); }
    static long constrTag(PlutusData d) { return Builtins.constrTag(d); }
    static PlutusData constrFields(PlutusData d) { return Builtins.constrFields(d); }
    static PlutusData constrData(PlutusData d) { return Builtins.constrData(3, Builtins.mkCons(d, Builtins.mkNilData())); }
    static PlutusData pairs(PlutusData k, PlutusData v) { return Builtins.mapData(Builtins.mkCons(Builtins.mkPairData(k, v), Builtins.mkNilPairData())); }
    static PlutusData failing() { return Builtins.error(); }
    static PlutusData traced(PlutusData d) { return Builtins.trace("seen", d); }
    static byte[] concat(byte[] a, byte[] b) { return Builtins.concat(a, b, a); }
    static byte[] append(byte[] a, byte[] b) { return Builtins.appendByteString(a, b); }
    static long length(byte[] a) { return Builtins.lengthOfByteString(a); }
    static byte[] slice(byte[] a) { return Builtins.sliceByteString(1, 2, a); }
    static byte[] hash(byte[] a) { return Builtins.blake2b_256(a); }
    static boolean bytesEqual(byte[] a, byte[] b) { return Builtins.equalsByteString(a, b); }
    static byte[] empty() { return Builtins.emptyByteString(); }
    static byte[] fromInteger(BigInteger a) { return Builtins.integerToByteString(true, 4, a); }
    static BigInteger toInteger(byte[] a) { return Builtins.byteStringToInteger(true, a); }

    static JulcList<BigInteger> emptyList() { return JulcList.empty(); }
    static PlutusData listLiteral(BigInteger a, byte[] b) { return Builtins.listData(JulcList.of(a, b, "c", true)); }
    static JulcMap<byte[], BigInteger> emptyMap() { return JulcMap.empty(); }
    static JulcArray<BigInteger> arrayOf(BigInteger a) { return JulcArray.of(a, a); }
    static JulcArray<BigInteger> arrayFromList(JulcList<BigInteger> l) { return JulcArray.fromList(l); }
    static Optional<BigInteger> some(BigInteger a) { return Optional.of(a); }
    static Optional<BigInteger> none() { return Optional.empty(); }

    static PubKeyHash hashOf(byte[] a) { return PubKeyHash.of(a); }
    static TxId txIdOf(byte[] a) { return TxId.of(a); }
    static Value zero() { return Value.zero(); }
    static Value lovelace(BigInteger a) { return Value.lovelace(a); }
    static Value singleton(PolicyId p, TokenName t, BigInteger q) { return Value.singleton(p, t, q); }
    static Interval always() { return Interval.always(); }
    static Interval never() { return Interval.never(); }
    static Interval after(BigInteger t) { return Interval.after(t); }
    static Interval before(BigInteger t) { return Interval.before(t); }
    static Interval between(BigInteger a, BigInteger b) { return Interval.between(a, b); }

    static Credential credentialFromData(PlutusData d) { return Credential.fromPlutusData(d); }
    static BigInteger castInteger(PlutusData d) { return PlutusData.cast(d, BigInteger.class); }
    static JulcMap<byte[], BigInteger> castMap(PlutusData d) { return PlutusData.cast(d, JulcMap.class); }
    static byte[] castHash(PlutusData d) { return PlutusData.cast(d, PubKeyHash.class).hash(); }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return Builtins.equalsData(redeemer, SEVEN_AS_DATA);
    }
}
