package org.julclang.difftest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-068 I11: declaration shapes the corpus does not hold, read by both frontends and compared. The outcome of each
 * shape is frozen in {@code adr068/shapes-m2.tsv}, so a shape where the frontends differ is written down, and a change
 * to any of them fails here. The list started as the differential probe of M2's evidence review.
 */
class Adr068ShapesTest {

    private static final Path FROZEN = Path.of("src/test/resources/adr068/shapes-m2.tsv");
    private static final String HEADER = """
            import java.math.BigInteger;
            import java.util.*;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.*;
            import org.julclang.ledger.*;
            import org.julclang.stdlib.annotation.*;
            """;

    private static final List<String> COMPONENTS = List.of(
            "BigInteger", "int", "long", "boolean", "byte[]", "String", "Boolean", "Integer", "Long",
            "PlutusData", "PlutusData.ConstrData", "PlutusData.MapData", "PlutusData.ListData", "PlutusData.IntData", "PlutusData.BytesData",
            "JulcList<BigInteger>", "List<BigInteger>", "JulcList", "List", "JulcMap<BigInteger, byte[]>", "Map<BigInteger, byte[]>", "JulcMap", "Map",
            "JulcAssocMap<BigInteger, byte[]>", "JulcAssocMap",
            "JulcArray<BigInteger>", "JulcArray", "Optional<BigInteger>", "Optional", "Optional<Optional<BigInteger>>",
            "Tuple2<BigInteger, byte[]>", "Tuple2", "Tuple3<BigInteger, byte[], String>", "Tuple3", "AssetEntry",
            "JulcValue", "JulcG1", "JulcG2", "JulcMlResult", "JulcScalars", "JulcG1Points", "JulcG2Points",
            "JulcList<JulcG1>", "JulcList<JulcValue>", "Optional<JulcValue>", "JulcMap<BigInteger, JulcG2>", "Tuple2<JulcValue, BigInteger>",
            "JulcList<JulcArray<BigInteger>>", "Optional<JulcArray<BigInteger>>", "Tuple2<JulcArray<BigInteger>, BigInteger>",
            "Value", "TxOut", "Address", "PubKeyHash", "PolicyId", "TokenName", "TxId", "Credential", "Credential.PubKeyCredential", "Interval",
            "ScriptInfo", "ScriptInfo.SpendingScript", "JulcList<Tuple2<BigInteger, BigInteger>>", "JulcMap<PubKeyHash, JulcList<Value>>",
            "List<JulcList<BigInteger>>", "Map<String, Map<String, BigInteger>>", "JulcList<?>", "JulcList<? extends BigInteger>", "JulcList<? super BigInteger>",
            "Optional<?>", "Object", "Comparable<BigInteger>", "ArrayList<BigInteger>", "byte", "short", "char", "float", "double", "int[]", "long[]", "byte[][]",
            "boolean[]", "String[]", "BigInteger[]", "Short", "Byte", "Character", "Double", "Float", "Void", "Number", "CharSequence", "Runnable",
            "java.math.BigInteger", "java.util.List<java.math.BigInteger>", "org.julclang.ledger.Value", "org.julclang.ledger.Credential.PubKeyCredential");

    private static final List<String> DECLARATIONS = List.of(
            "record R<T>(T v) {} class P {}",
            "record R<T>(BigInteger v) {} class P {}",
            "record R<T extends BigInteger>(T v) {} class P {}",
            "record Box<T>(T v) {} record H(Box<BigInteger> b) {} class P {}",
            "sealed interface S<T> { record A(BigInteger v) implements S<BigInteger> {} record B() implements S<BigInteger> {} } class P {}",
            "sealed interface S<T> { record A<T>(T v) implements S<T> {} record B<T>() implements S<T> {} } class P {}",
            "sealed interface S<T> { record A<T>(T v) implements S<T> {} } record H(S<BigInteger> s) {} class P {}",
            "sealed interface S permits A, B {} record A() implements S {} record B() implements S {} class P {}",
            "sealed interface S permits S.A, B {record A() implements S {}} record B() implements S {} class P {}",
            "sealed interface S permits S.B, S.A { record A() implements S {} record B(BigInteger v) implements S {} } class P {}",
            "sealed interface S {} final class C implements S {} class P {}",
            "sealed interface S permits C {} final class C implements S {} class P {}",
            "sealed interface S permits T {} sealed interface T extends S permits R {} record R() implements T {} class P {}",
            "sealed interface S permits S.A, T {record A() implements S {}} non-sealed interface T extends S {} class P {}",
            "sealed interface S { record A() implements S {} final class C implements S {} } class P {}",
            "sealed interface S { record A() implements S {} enum E implements S { X } } class P {}",
            "sealed abstract class S permits A {} final class A extends S {} class P {}",
            "sealed interface S1 { record A() implements S1, S2 {} } sealed interface S2 permits S1.A {} class P {}",
            "sealed interface S1 permits R {} sealed interface S2 permits R {} record R(BigInteger v) implements S1, S2 {} class P {}",
            "interface I { record R(BigInteger v) {} } class P {}",
            "interface I { record R(BigInteger v) implements I {} } class P {}",
            "enum E { A, B } record R(E e) {} class P {}",
            "record R(BigInteger a, BigInteger b) { R { } } class P {}",
            "record R(BigInteger a, BigInteger b) { R(BigInteger b, BigInteger a) { this.a = a; this.b = b; } } class P {}",
            "record R(BigInteger a) { static BigInteger z = BigInteger.ZERO; } class P {}",
            "record R(BigInteger a) { record N(R r) {} } class P {}",
            "record R(BigInteger a) implements Comparable<R> { public int compareTo(R o) { return 0; } } class P {}",
            "record R(BigInteger... xs) {} class P {}",
            "record R(byte... xs) {} class P {}",
            "record R(final BigInteger a) {} class P {}",
            "record R(@Deprecated BigInteger a) {} class P {}",
            "class P { record R(BigInteger a) {} static class Q { record R(byte[] b) {} } record H(R r, Q.R q) {} }",
            "class P { interface A { record R(BigInteger a) {} } interface B { record R(byte[] a) {} } record H(A.R a, B.R b) {} }",
            "@NewType record N(byte[] v) {} class P {}",
            "@NewType record N(BigInteger v) {} class P {}",
            "@NewType record N(String v) {} class P {}",
            "@NewType record N(boolean v) {} class P {}",
            "@NewType record N(Boolean v) {} class P {}",
            "@NewType record N(int v) {} class P {}",
            "@NewType record N(long v) {} class P {}",
            "@NewType record N(PlutusData v) {} class P {}",
            "@NewType record N(java.math.BigInteger v) {} class P {}",
            "@NewType record N(java.lang.String v) {} class P {}",
            "@NewType record N(byte... v) {} class P {}",
            "@NewType() record N(byte[] v) {} class P {}",
            "@org.julclang.stdlib.annotation.NewType record N(byte[] v) {} class P {}",
            "@NewType record N<T>(byte[] v) {} class P {}",
            "@NewType record N(byte[] v) implements PlutusDataConvertible { public PlutusData toPlutusData() { return null; } } class P {}",
            "@NewType record N(byte[] v) {} record H(N n, JulcList<N> ns, Optional<N> o) {} class P {}",
            "@NewType record N(byte[] v) {} @NewType record M(N n) {} class P {}",
            "@NewType sealed interface S { record A() implements S {} } class P {}",
            "@NewType class N {} class P {}",
            "sealed interface S { @NewType record A(byte[] v) implements S {} record B() implements S {} } class P {}",
            "record A(B b) {} record B(JulcList<A> as) {} class P {}",
            "record A(Optional<A> next) {} class P {}",
            "record A(JulcList<A> kids) {} class P {}",
            "record A(JulcMap<BigInteger, A> kids) {} class P {}",
            "sealed interface T { record Leaf() implements T {} record Node(T l, T r) implements T {} } class P {}",
            "sealed interface T { record Node(T l) implements T {} } class P {}",
            "record Value(BigInteger x) {} record H(Value v) {} class P {}",
            "record TxOut(BigInteger x) {} record H(TxOut v) {} class P {}",
            "record Address(BigInteger x) {} class P {}",
            "sealed interface Credential { record A() implements Credential {} } record H(Credential c) {} class P {}",
            "sealed interface Vote permits Vote.Yes { record Yes() implements Vote {} } class P {}",
            "class P { record Value(BigInteger x) {} record H(Value v) {} }",
            "class P { record PubKeyHash(byte[] h) {} record H(PubKeyHash v) {} }",
            "class Q { record Inner(BigInteger x) {} } record H(Q.Inner i) {} class P {}",
            "class P { static void m() { record L(BigInteger v) {} } }",
            "class P { static void m() { record L(BigInteger v) {} record M(L l) {} } }",
            "class P { static void m() { sealed interface S permits A {} record A() implements S {} } }",
            "class Other { interface S {} } sealed interface S { record A() implements Other.S {} record B() implements S {} } class P {}",
            "class P { record R(BigInteger x, byte[] a) { R(byte[] y, byte... z) { this(BigInteger.ONE, z); } } }",
            "class P { sealed interface S permits A, B {} record A() implements S {} record B() implements S {} static <T> boolean u(T x) { record S(byte[] b) {} return true; } }");

    @Test
    void everyShapeReadsAsFrozen() throws Exception {
        var rows = new ArrayList<String>();
        for (String component : COMPONENTS) {
            rows.add(row("component\t" + component, HEADER + "record R(" + component + " x) {} class P {}"));
            rows.add(row("in-sealed\t" + component, HEADER + "sealed interface S { record A(" + component + " x) implements S {} record B() implements S {} } class P {}"));
        }
        for (String declaration : DECLARATIONS) rows.add(row("decl\t" + declaration, HEADER + declaration));
        for (String declaration : DECLARATIONS) rows.add(row("pkg\t" + declaration, "package a.b;\n" + HEADER + declaration));
        String freeze = System.getenv("ADR068_FREEZE_SHAPES");
        if (freeze != null) Files.write(Path.of(freeze), rows);
        assertEquals(Files.readAllLines(FROZEN), rows);
    }

    private static String row(String label, String source) {
        var units = List.of(source);
        var reference = Adr068Types.reference(units);
        var javac = Adr068Types.javac(units, List.of());
        String verdict;
        if (reference.accepted() && javac.accepted()) {
            String difference = Adr068Types.difference(reference.registry(), javac.registry());
            verdict = difference == null ? "equal\t" : "DIFFERENT\t" + difference;
        } else if (!reference.accepted() && !javac.accepted()) {
            verdict = (reference.rejection().equals(javac.rejection()) ? "both-reject-same\t" : "both-reject\t")
                    + reference.rejection() + " /// " + javac.rejection();
        } else if (reference.accepted()) {
            verdict = "JAVAC-REJECTS\t" + javac.rejection();
        } else {
            verdict = "REFERENCE-REJECTS\t" + reference.rejection();
        }
        return label.replace('\n', ' ') + "\t" + verdict.replace('\n', ' ');
    }
}
