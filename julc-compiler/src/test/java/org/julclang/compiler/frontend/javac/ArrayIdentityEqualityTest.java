package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.julclang.core.PlutusData;
import org.julclang.ledger.DatumHash;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.ValidatorHash;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D6's {@code JULC0087} predicate on javac's types: direct, nested record, sealed-component, container,
 * {@code java.util}, type-argument and recursive-type paths, the allow-list, explicit {@code equals}, and the
 * equality-operation table.
 */
class ArrayIdentityEqualityTest {

    private static final String SOURCE = """
            package probe;

            import java.math.BigInteger;
            import java.util.List;
            import java.util.Map;
            import java.util.Optional;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcArray;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.core.types.Tuple2;
            import org.julclang.ledger.PubKeyHash;
            import org.julclang.ledger.TxOut;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.lib.TypedMapLib;

            public class Probe {
                record Bytes(byte[] b) {}
                record Wrapped(Bytes inner) {}
                record Plain(BigInteger x, String y, boolean z) {}
                record Declares(BigInteger x) {
                    public boolean equals(Object o) { return o instanceof Declares d && d.x.equals(x); }
                }
                record DeclaresFinal(BigInteger x) {
                    public final boolean equals(Object o) { return o instanceof DeclaresFinal d && d.x.equals(x); }
                }
                record Box<T>(T value) {}
                sealed interface Shape { record Circle(BigInteger r) implements Shape {} record Blob(byte[] b) implements Shape {} }
                sealed interface Safe { record One(BigInteger r) implements Safe {} record Two(PubKeyHash k) implements Safe {} }
                sealed interface Opt<T> { record Some<T>(T value) implements Opt<T> {} record None<T>() implements Opt<T> {} }
                sealed interface Tree { record Leaf(BigInteger v) implements Tree {} record Node(Tree l, Tree r) implements Tree {} }
                sealed interface BTree { record BLeaf(byte[] v) implements BTree {} record BNode(BTree l, BTree r) implements BTree {} }
                enum Colour { RED, GREEN }
                static class Hidden<T> extends java.util.ArrayList<byte[]> {}
                static class Parent extends java.util.ArrayList<String> {
                    byte[] tag;
                    public boolean equals(Object o) { return o instanceof Parent p && p.tag == tag; }
                    public int hashCode() { return super.hashCode(); }
                }
                static class Child extends Parent {}
                static class Grandchild extends Child {}
                static class PlainChild extends java.util.ArrayList<String> {}
                record Overload(byte[] b) { public boolean equals(String s) { return false; } }

                static byte[] bytes;
                static BigInteger integer;
                static int primitive;
                static String string;
                static Boolean boxedBoolean;
                static Integer boxedInteger;
                static Bytes bytesRecord;
                static Wrapped wrapped;
                static Plain plain;
                static Declares declares;
                static DeclaresFinal declaresFinal;
                static Shape shape;
                static Safe safe;
                static Opt<byte[]> optBytes;
                static Opt<BigInteger> optInteger;
                static Box<byte[]> boxBytes;
                static Box<BigInteger> boxInteger;
                static Tree tree;
                static BTree bytesTree;
                static Colour colour;
                static Optional<byte[]> optionalBytes;
                static Optional<PubKeyHash> optionalHash;
                static Tuple2<BigInteger, byte[]> tupleBytes;
                static JulcList<byte[]> julcListBytes;
                static JulcMap<byte[], BigInteger> julcMapBytes;
                static JulcArray<byte[]> julcArrayBytes;
                static JulcList<Bytes> julcListRecord;
                static JulcList<JulcList<byte[]>> nestedJulcList;
                static JulcList<?> wildcard;
                static List<byte[]> utilListBytes;
                static Map<BigInteger, byte[]> utilMapBytes;
                static List<BigInteger> utilListInteger;
                static PlutusData data;
                static PlutusData.ListData listData;
                static PubKeyHash pubKeyHash;
                static TxOut txOut;
                static ScriptContext scriptContext;
                static Object object;
                static Hidden<String> hidden;
                static Parent parent;
                static Child child;
                static Grandchild grandchild;
                static PlainChild plainChild;
                static Overload overload;
                static List<Integer> integers;
                static Long boxedLong;

                static <T> boolean generic(T a, T b) { return a.equals(b); }
                static <L extends List<byte[]>> boolean bounded(L xs, String s) { return xs.contains(s); }

                static boolean calls(JulcList<byte[]> xs, JulcMap<Bytes, BigInteger> m, List<byte[]> ys, Bytes k) {
                    boolean r = xs.contains(bytes);
                    r = r && m.containsKey(k);
                    r = r && m.getOrDefault(k, integer) != null;
                    r = r && TypedMapLib.member(m, k);
                    r = r && ys.contains(bytes);
                    r = r && ys.remove(0) != null;
                    r = r && java.util.Objects.equals(bytes, bytes);
                    r = r && plain.equals(plain);
                    r = r && ((JulcMap) (Object) m).containsKey(pubKeyHash);
                    r = r && utilMapBytes.remove(integer, bytes);
                    r = r && hidden.equals(hidden);
                    m.insert(k, integer);
                    return r;
                }
            }
            """;

    private static Types types;
    private static Elements elements;
    private static Trees trees;
    private static TypeElement probe;
    private static ArrayIdentityEquality check;
    private static final List<String> sites = new ArrayList<>();

    @BeforeAll
    static void analyze() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///probe/Probe.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return SOURCE;
            }
        };
        var task = (JavacTask) compiler.getTask(null, null, diagnostics,
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path")), null, List.of(file));
        var units = task.parse();
        task.analyze();
        for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) throw new AssertionError(d.toString());
        }
        types = task.getTypes();
        elements = task.getElements();
        trees = Trees.instance(task);
        probe = elements.getTypeElement("probe.Probe");
        check = new ArrayIdentityEquality(types, elements, trees);
        var operations = new EqualityOperations(types, elements);
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                var path = getCurrentPath();
                var method = (ExecutableElement) trees.getElement(path);
                TypeMirror receiver = null;
                if (node.getMethodSelect() instanceof MemberSelectTree select) {
                    receiver = trees.getTypeMirror(new TreePath(new TreePath(path, node.getMethodSelect()), select.getExpression()));
                }
                var arguments = node.getArguments().stream()
                        .map(a -> trees.getTypeMirror(new TreePath(path, a))).toList();
                for (var site : operations.sites(method, receiver, arguments)) {
                    sites.add(site.operation() + " " + site.compared() + " "
                            + check.check(site.compared(), site.julcCollection()).kind());
                }
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(units.iterator().next(), null);
    }

    private static TypeMirror field(String name) {
        return probe.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.FIELD && e.getSimpleName().contentEquals(name))
                .map(e -> ((VariableElement) e).asType()).findFirst().orElseThrow();
    }

    private static ArrayIdentityEquality.Kind kind(String field) {
        return check.check(field(field)).kind();
    }

    @Test
    void directTypes() {
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, kind("bytes"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("integer"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("primitive"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("string"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("boxedBoolean"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("colour"));
        // ADR-068 M0's clarification puts the boxed Integer, Long and Boolean on the allow-list (F307).
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("boxedInteger"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("boxedLong"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("integers"));
        assertEquals(ArrayIdentityEquality.Kind.UNRESOLVED, kind("object"));
    }

    @Test
    void recordsAreCheckedComponentByComponent() {
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, kind("bytesRecord"));
        var nested = check.check(field("wrapped"));
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, nested.kind());
        assertEquals("Wrapped.inner > Bytes.b > byte[]", nested.path());
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("plain"));
    }

    @Test
    void declaringEqualsIsNotEnough() {
        assertEquals(ArrayIdentityEquality.Kind.UNRESOLVED, kind("declares"));
        // An explicit final equals has the implicit one's modifiers; its tree tells them apart (F305).
        assertEquals(ArrayIdentityEquality.Kind.UNRESOLVED, kind("declaresFinal"));
        // equals(String) is an overload, not an override: the implicit equals(Object) still compares the array.
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, kind("overload"));
        // Without trees a source record's equals cannot be told apart, so it is taken as explicit.
        assertEquals(ArrayIdentityEquality.Kind.UNRESOLVED,
                new ArrayIdentityEquality(types, elements).check(field("plain")).kind());
    }

    /** A collection is checked at its instantiated collection supertype, not its own type arguments (F306). */
    @Test
    void collectionSupertypes() {
        var hidden = check.check(field("hidden"));
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, hidden.kind());
        assertEquals("Hidden as Collection > Collection<0> > byte[]", hidden.path());
    }

    /**
     * F316: a collection subclass that declares no {@code equals} runs the nearest superclass's; when that is a
     * custom one (here comparing a {@code byte[]} field by identity), the collection equality is not the one assumed.
     */
    @Test
    void inheritedCustomEqualsIsUnresolved() {
        assertEquals(ArrayIdentityEquality.Kind.UNRESOLVED, kind("parent"));
        var child = check.check(field("child"));
        assertEquals(ArrayIdentityEquality.Kind.UNRESOLVED, child.kind());
        assertEquals("probe.Probe.Child inherits equals from probe.Probe.Parent, which is not on the value-equality"
                + " allow-list", child.path());
        assertEquals(ArrayIdentityEquality.Kind.UNRESOLVED, kind("grandchild"));
        // A subclass that inherits java.util's equals unchanged is still a collection of its elements.
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("plainChild"));
    }

    @Test
    void classFileRecordsTellImplicitFromExplicitEquals() {
        assertFalse(check.declaresEquals(elements.getTypeElement("org.julclang.ledger.TxOut")));
        assertTrue(check.declaresEquals(elements.getTypeElement("org.julclang.ledger.PubKeyHash")));
        assertFalse(check.declaresEquals(elements.getTypeElement("probe.Probe.Plain")));
        assertTrue(check.declaresEquals(elements.getTypeElement("probe.Probe.Declares")));
    }

    /**
     * Source and class-file provenance agree: the same records, read from source and from the class files javac
     * writes for them, are classified alike, including an explicit {@code final equals} and an overload (F305).
     */
    @Test
    void sourceAndClassFileProvenanceAgree(@TempDir Path classes) throws Exception {
        String records = """
                package parity;
                public class Records {
                    public record Implicit(byte[] b) {}
                    public record Explicit(byte[] b) { public boolean equals(Object o) { return false; } }
                    public record ExplicitFinal(byte[] b) { public final boolean equals(Object o) { return false; } }
                    public record Overload(byte[] b) { public boolean equals(String s) { return false; } }
                }
                """;
        var names = List.of("Implicit", "Explicit", "ExplicitFinal", "Overload");
        var fromSource = new ArrayList<Boolean>();
        var sourceTask = task(List.of(unit("parity/Records.java", records)), List.of("-d", classes.toString()));
        sourceTask.analyze();
        var sourceCheck = new ArrayIdentityEquality(sourceTask.getTypes(), sourceTask.getElements(), Trees.instance(sourceTask));
        for (String name : names) fromSource.add(sourceCheck.declaresEquals(sourceTask.getElements().getTypeElement("parity.Records." + name)));
        sourceTask.generate();
        var fromClasses = new ArrayList<Boolean>();
        var classTask = task(List.of(unit("parity/User.java", "package parity; class User { Records r; }")),
                List.of("-classpath", classes + java.io.File.pathSeparator + System.getProperty("java.class.path")));
        classTask.analyze();
        var classCheck = new ArrayIdentityEquality(classTask.getTypes(), classTask.getElements(), Trees.instance(classTask));
        for (String name : names) {
            var element = classTask.getElements().getTypeElement("parity.Records." + name);
            assertEquals(JavaFileObject.Kind.CLASS, classTask.getElements().getFileObjectOf(element).getKind(), name);
            fromClasses.add(classCheck.declaresEquals(element));
        }
        assertEquals(List.of(false, true, true, false), fromSource);
        assertEquals(fromSource, fromClasses);
    }

    private static JavacTask task(List<JavaFileObject> files, List<String> extra) {
        var options = new ArrayList<>(List.of("-proc:none", "-classpath", System.getProperty("java.class.path")));
        options.addAll(extra);
        return (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null, null, options, null, files);
    }

    private static JavaFileObject unit(String path, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }

    @Test
    void sealedTypesAreCheckedVariantByVariant() {
        var shape = check.check(field("shape"));
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, shape.kind());
        assertEquals("Shape|Blob > Blob.b > byte[]", shape.path());
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("safe"));
    }

    @Test
    void typeArgumentsAreSubstituted() {
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, kind("boxBytes"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("boxInteger"));
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, kind("optBytes"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("optInteger"));
    }

    @Test
    void containers() {
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, kind("optionalBytes"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("optionalHash"));
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, kind("tupleBytes"));
        // byte[] in a JuLC collection is compared deeply on the JVM after M1.
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("julcListBytes"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("julcMapBytes"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("julcArrayBytes"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("nestedJulcList"));
        // ... but not a record holding one.
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, kind("julcListRecord"));
        assertEquals(ArrayIdentityEquality.Kind.UNRESOLVED, kind("wildcard"));
    }

    @Test
    void javaUtilCollections() {
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, kind("utilListBytes"));
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, kind("utilMapBytes"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("utilListInteger"));
    }

    @Test
    void recursiveTypesTerminate() {
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("tree"));
        assertEquals(ArrayIdentityEquality.Kind.ARRAY_IDENTITY, kind("bytesTree"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("data"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("listData"));
    }

    @Test
    void ledgerTypes() {
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("pubKeyHash"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("txOut"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, kind("scriptContext"));
    }

    @Test
    void typeVariablesAreDecidedWhereBound() {
        var generic = probe.getEnclosedElements().stream()
                .filter(e -> e.getSimpleName().contentEquals("generic")).findFirst().orElseThrow();
        var parameter = ((ExecutableElement) generic).getParameters().getFirst().asType();
        assertEquals(ArrayIdentityEquality.Kind.TYPE_VARIABLE, check.check(parameter).kind());
    }

    @Test
    void theOperationTableFindsTheComparedType() {
        assertEquals(List.of(
                "BigInteger.equals java.math.BigInteger SAFE",
                "BigInteger.equals java.math.BigInteger SAFE",
                "Object.equals T TYPE_VARIABLE",
                "List.contains byte[] ARRAY_IDENTITY",
                "JulcList.contains byte[] SAFE",
                "JulcMap.containsKey probe.Probe.Bytes ARRAY_IDENTITY",
                "JulcMap.getOrDefault probe.Probe.Bytes ARRAY_IDENTITY",
                "List.contains byte[] ARRAY_IDENTITY",
                "Objects.equals byte[] ARRAY_IDENTITY",
                "Plain.equals probe.Probe.Plain SAFE",
                "JulcMap.containsKey org.julclang.ledger.PubKeyHash SAFE",
                "Map.remove java.math.BigInteger SAFE",
                "Map.remove byte[] ARRAY_IDENTITY",
                "ArrayList.equals probe.Probe.Hidden<java.lang.String> ARRAY_IDENTITY"), sites);
    }

    /** Each allow-list entry compares every component by value: distinct equal arrays are equal. */
    @Test
    void allowListedTypesCompareByValue() {
        assertValueEquality(PubKeyHash::new);
        assertValueEquality(PolicyId::new);
        assertValueEquality(TxId::new);
        assertValueEquality(TokenName::new);
        assertValueEquality(ScriptHash::new);
        assertValueEquality(ValidatorHash::new);
        assertValueEquality(DatumHash::new);
        assertValueEquality(PlutusData.BytesData::new);
        assertValueEquality(b -> new PlutusData.IntData(new BigInteger(1, b)));
        assertValueEquality(b -> Integer.valueOf(1000 + b[2]));
        assertValueEquality(b -> Long.valueOf(1000L + b[2]));
        assertValueEquality(b -> new PlutusData.ConstrData(1, List.of(new PlutusData.BytesData(b))));
        assertValueEquality(b -> new PlutusData.ListData(List.of(new PlutusData.BytesData(b))));
        assertValueEquality(b -> new PlutusData.MapData(List.of(
                new PlutusData.Pair(new PlutusData.BytesData(b), new PlutusData.BytesData(b)))));
        assertEquals(new BigInteger("12345678901234567890"), new BigInteger("12345678901234567890"));
        assertEquals(new String(new char[]{'a'}), new String(new char[]{'a'}));
        assertEquals(Boolean.valueOf("true"), Boolean.valueOf("true"));
        assertEquals(17, ArrayIdentityEquality.VALUE_EQUALITY_ALLOW_LIST.size(),
                "every allow-list entry has a value-equality assertion above");
    }

    private static void assertValueEquality(Function<byte[], Object> make) {
        Object a = make.apply(new byte[]{1, 2, 3});
        Object b = make.apply(new byte[]{1, 2, 3});
        Object c = make.apply(new byte[]{1, 2, 4});
        assertEquals(a, b, a.getClass().getName());
        assertNotEquals(a, c, a.getClass().getName());
        assertTrue(ArrayIdentityEquality.VALUE_EQUALITY_ALLOW_LIST.contains(a.getClass().getCanonicalName()),
                a.getClass().getCanonicalName());
    }
}
