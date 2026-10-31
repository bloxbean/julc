package org.julclang.compiler.frontend.javac;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A build check over the on-chain API's class files (julc-core, julc-ledger-api, julc-stdlib), for ADR-068 D6's
 * value-equality allow-list (F204): every on-chain API record that declares its own {@code equals(Object)} is on
 * the allow-list, and no API record declares it {@code final} (so that the implicit and explicit methods stay apart by
 * modifiers too, not only by {@link ArrayIdentityEquality#implicitRecordEquals provenance}).
 */
class ApiRecordEqualsTest {

    @Test
    void apiRecordsWithExplicitEqualsAreAllowListedAndNotFinal() throws IOException {
        var explicit = new TreeSet<String>();
        var finalExplicit = new TreeSet<String>();
        int records = 0;
        for (byte[] bytes : apiClassFiles()) {
            ClassModel model = ClassFile.of().parse(bytes);
            if (model.findAttribute(Attributes.record()).isEmpty()) continue;
            records++;
            String name = model.thisClass().asInternalName().replace('/', '.').replace('$', '.');
            if (ArrayIdentityEquality.implicitRecordEquals(bytes)) continue;
            for (var method : model.methods()) {
                if (method.methodName().equalsString("equals") && method.methodType().equalsString("(Ljava/lang/Object;)Z")) {
                    explicit.add(name);
                    if (method.flags().has(AccessFlag.FINAL)) finalExplicit.add(name);
                }
            }
        }
        assertTrue(records > 50, "API records found: " + records);
        assertEquals(new TreeSet<>(), finalExplicit, "API records declaring a final equals");
        var notAllowed = new TreeSet<>(explicit.stream().filter(ApiRecordEqualsTest::onChainApi).toList());
        notAllowed.removeAll(ArrayIdentityEquality.VALUE_EQUALITY_ALLOW_LIST);
        assertEquals(new TreeSet<>(), notAllowed, "API records declaring equals but not on the allow-list");
    }

    /**
     * The on-chain API: ledger and stdlib types, {@code PlutusData} and JuLC's collection types. julc-core's UPLC AST
     * ({@code org.julclang.core.Constant} and the like) is visible to javac but not on-chain types (I10), and an
     * explicit {@code equals} there is simply rejected conservatively.
     */
    private static boolean onChainApi(String name) {
        return name.startsWith("org.julclang.ledger.") || name.startsWith("org.julclang.stdlib.")
                || name.startsWith("org.julclang.core.PlutusData") || name.startsWith("org.julclang.core.types.");
    }

    private static List<byte[]> apiClassFiles() throws IOException {
        var result = new ArrayList<byte[]>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!(entry.contains("julc-core") || entry.contains("julc-ledger-api") || entry.contains("julc-stdlib"))) continue;
            if (entry.contains("/test")) continue;
            Path path = Path.of(entry);
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    for (Path p : walk.filter(f -> f.toString().endsWith(".class")).sorted().toList()) result.add(Files.readAllBytes(p));
                }
            } else if (entry.endsWith(".jar") && Files.exists(path)) {
                try (var jar = new JarFile(path.toFile())) {
                    for (var e : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                        try (var in = jar.getInputStream(e)) {
                            result.add(in.readAllBytes());
                        }
                    }
                }
            }
        }
        return result;
    }
}
