package org.julclang.compiler.frontend.javac;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.reflect.AccessFlag;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ArrayIdentityEquality#implicitRecordEquals} on class files: only javac's canonical implicit body is
 * implicit (F317), and API stubs without method code are classified by their modifiers (F210).
 */
class RecordEqualsClassFileTest {

    private static final String RECORDS = """
            package cf;
            public class Records {
                public record N(java.math.BigInteger v) {}
                public record Two(java.math.BigInteger a, byte[] b) {}
                public record Empty() {}
                public record Explicit(java.math.BigInteger v) { public boolean equals(Object o) { return true; } }
            }
            """;

    /** F317's probe: the canonical body is implicit, and a transformed one that still calls the bootstrap is not. */
    @Test
    void onlyTheCanonicalBodyIsImplicit(@TempDir Path classes) throws Exception {
        var compiled = compile(classes);
        byte[] n = compiled.get("cf/Records$N.class");
        assertTrue(ArrayIdentityEquality.implicitRecordEquals(n));
        assertTrue(ArrayIdentityEquality.implicitRecordEquals(compiled.get("cf/Records$Two.class")));
        assertTrue(ArrayIdentityEquality.implicitRecordEquals(compiled.get("cf/Records$Empty.class")));
        assertFalse(ArrayIdentityEquality.implicitRecordEquals(compiled.get("cf/Records$Explicit.class")));
        assertFalse(ArrayIdentityEquality.implicitRecordEquals(compiled.get("cf/Records.class")), "not a record");

        // The result inverted after the ObjectMethods call: the bootstrap is still invoked, the semantics are not
        // javac's.
        byte[] inverted = transformEquals(n, (builder, element) -> {
            if (element instanceof ReturnInstruction) {
                builder.iconst_1();
                builder.ixor();
            }
            builder.with(element);
        });
        assertFalse(ArrayIdentityEquality.implicitRecordEquals(inverted));
        assertEquals(false, equalsOfLoaded(inverted, "cf.Records$N"), "the mutated class's N(1).equals(N(1))");
        assertEquals(true, equalsOfLoaded(n, "cf.Records$N"));

        // An extra instruction, or the method no longer final, is not the canonical form either.
        assertFalse(ArrayIdentityEquality.implicitRecordEquals(transformEquals(n, (builder, element) -> {
            if (element instanceof ReturnInstruction) builder.nop();
            builder.with(element);
        })));
        var file = ClassFile.of();
        byte[] notFinal = file.transformClass(file.parse(n), ClassTransform.transformingMethods(isEquals(),
                (builder, element) -> {
                    if (element instanceof java.lang.classfile.AccessFlags) builder.withFlags(AccessFlag.PUBLIC);
                    else builder.with(element);
                }));
        assertFalse(ArrayIdentityEquality.implicitRecordEquals(notFinal));

        // A bootstrap naming other components than the record's is not its implicit equals.
        assertFalse(ArrayIdentityEquality.implicitRecordEquals(renameComponents(n, "w")));
        assertTrue(ArrayIdentityEquality.implicitRecordEquals(renameComponents(n, "v")), "the control");
    }

    /**
     * F210: M1a's stubs drop method code ({@code JavacPlatformTool.strip}). Over the API's class files stripped the
     * same way, every record keeps its classification, and the predicate on javac's types still finds
     * {@code TxOut} safe (implicit {@code equals}) and {@code PubKeyHash} explicit and allow-listed.
     */
    @Test
    void strippedStubsKeepTheClassification(@TempDir Path stubs) throws Exception {
        var original = apiClassFiles();
        int records = 0;
        for (var entry : original.entrySet()) {
            byte[] stripped = strip(entry.getValue());
            Path target = stubs.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, stripped);
            ClassModel model = ClassFile.of().parse(stripped);
            for (MethodModel method : model.methods()) assertTrue(method.code().isEmpty(), entry.getKey());
            if (model.findAttribute(java.lang.classfile.Attributes.record()).isEmpty()) continue;
            records++;
            assertEquals(ArrayIdentityEquality.implicitRecordEquals(entry.getValue()),
                    ArrayIdentityEquality.implicitRecordEquals(stripped), entry.getKey());
        }
        assertTrue(records > 50, "API records: " + records);
        assertTrue(original.containsKey("org/julclang/ledger/TxOut.class"));

        var classPath = new ArrayList<String>();
        classPath.add(stubs.toString());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!isApi(entry)) classPath.add(entry);
        }
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null, null,
                List.of("-proc:none", "-classpath", String.join(File.pathSeparator, classPath)), null,
                List.of(unit("user/U.java", "package user; class U { org.julclang.ledger.TxOut o; org.julclang.ledger.PubKeyHash k; }")));
        task.analyze();
        var elements = task.getElements();
        var check = new ArrayIdentityEquality(task.getTypes(), elements, Trees.instance(task));
        TypeElement txOut = elements.getTypeElement("org.julclang.ledger.TxOut");
        TypeElement pubKeyHash = elements.getTypeElement("org.julclang.ledger.PubKeyHash");
        var txOutFile = elements.getFileObjectOf(txOut);
        assertEquals(JavaFileObject.Kind.CLASS, txOutFile.getKind());
        try (var in = txOutFile.openInputStream()) {
            var read = ClassFile.of().parse(in.readAllBytes());
            assertTrue(read.methods().stream().allMatch(m -> m.code().isEmpty()), "javac read the stub: " + txOutFile.toUri());
        }
        assertFalse(check.declaresEquals(txOut));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, check.check(txOut.asType()).kind());
        assertTrue(check.declaresEquals(pubKeyHash));
        assertTrue(ArrayIdentityEquality.VALUE_EQUALITY_ALLOW_LIST.contains("org.julclang.ledger.PubKeyHash"));
        assertEquals(ArrayIdentityEquality.Kind.SAFE, check.check(pubKeyHash.asType()).kind());
    }

    /** M1a's {@code JavacPlatformTool.strip}: method bodies and private members dropped, everything else kept. */
    static byte[] strip(byte[] bytes) {
        ClassFile file = ClassFile.of(ClassFile.ConstantPoolSharingOption.NEW_POOL);
        ClassTransform transform = ClassTransform.transformingMethods(MethodTransform.dropping(e -> e instanceof CodeModel))
                .andThen(ClassTransform.dropping(e -> (e instanceof MethodModel m && m.flags().has(AccessFlag.PRIVATE))
                        || (e instanceof FieldModel f && f.flags().has(AccessFlag.PRIVATE))));
        return file.transformClass(file.parse(bytes), transform);
    }

    private static java.util.function.Predicate<MethodModel> isEquals() {
        return m -> m.methodName().equalsString("equals") && m.methodType().equalsString("(Ljava/lang/Object;)Z");
    }

    private static byte[] transformEquals(byte[] bytes, CodeTransform transform) {
        var file = ClassFile.of();
        return file.transformClass(file.parse(bytes), ClassTransform.transformingMethodBodies(isEquals(), transform));
    }

    /** {@code bytes} with its {@code equals} call site's component names changed: another record's bootstrap. */
    private static byte[] renameComponents(byte[] bytes, String names) {
        return transformEquals(bytes, (builder, element) -> {
            if (element instanceof InvokeDynamicInstruction indy) {
                var arguments = new ArrayList<>(indy.bootstrapArgs());
                arguments.set(1, names);
                builder.invokedynamic(DynamicCallSiteDesc.of(indy.bootstrapMethod(), indy.name().stringValue(),
                        indy.typeSymbol(), arguments.toArray(ConstantDesc[]::new)));
            } else {
                builder.with(element);
            }
        });
    }

    /** Loads the transformed class and evaluates {@code new C(1).equals(new C(1))}. */
    private static Object equalsOfLoaded(byte[] bytes, String name) throws Exception {
        var loader = new ClassLoader(RecordEqualsClassFileTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String n) throws ClassNotFoundException {
                if (n.equals(name)) return defineClass(n, bytes, 0, bytes.length);
                throw new ClassNotFoundException(n);
            }
        };
        Class<?> type = loader.loadClass(name);
        var constructor = type.getDeclaredConstructor(BigInteger.class);
        constructor.setAccessible(true);
        return constructor.newInstance(BigInteger.ONE).equals(constructor.newInstance(BigInteger.ONE));
    }

    private static Map<String, byte[]> compile(Path classes) throws IOException {
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null, null,
                List.of("-proc:none", "-d", classes.toString()), null, List.of(unit("cf/Records.java", RECORDS)));
        assertTrue(task.call());
        var result = new TreeMap<String, byte[]>();
        try (Stream<Path> walk = Files.walk(classes)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                result.put(classes.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return result;
    }

    private static boolean isApi(String entry) {
        return (entry.contains("julc-core") || entry.contains("julc-ledger-api")) && !entry.contains("/test");
    }

    /** The class files of julc-core and julc-ledger-api on the test class path, by relative path. */
    private static Map<String, byte[]> apiClassFiles() throws IOException {
        var result = new TreeMap<String, byte[]>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!isApi(entry)) continue;
            Path path = Path.of(entry);
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                        result.put(path.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                    }
                }
            } else if (entry.endsWith(".jar") && Files.exists(path)) {
                try (var jar = new JarFile(path.toFile())) {
                    for (var e : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                        try (var in = jar.getInputStream(e)) {
                            result.put(e.getName(), in.readAllBytes());
                        }
                    }
                }
            }
        }
        result.keySet().removeIf(name -> name.endsWith("module-info.class") || name.startsWith("META-INF/"));
        return result;
    }

    private static JavaFileObject unit(String path, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }
}
