package org.julclang.compiler.frontend.javac.tool;

import org.julclang.compiler.frontend.javac.JavacPlatform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.Attributes;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 I11: the stub generator's check, on the shipped stubs, and on stubs broken one way each: every break of an
 * attribute javac reads, and every reordering of a sealed ledger type, is a finding. The generator writes no blob with
 * a finding ({@code JavacPlatformTool.stubs}).
 */
class StubAttributesTest {

    private static final Path LEDGER_SOURCES = Path.of("../julc-ledger-api/src/main/java");
    private static Map<String, byte[]> stubs;
    private static Map<String, byte[]> originals;

    /**
     * The check compares each stub with its class, so a class on this test's class path stands in for the input. The
     * verification annotations are not on it (julc-verification depends on this module): their stubs stand in for
     * themselves, so these tests say nothing about them; the build's own run of the check does.
     */
    @BeforeAll
    static void load() throws IOException {
        stubs = JavacPlatform.read(JavacPlatform.resource(JavacPlatform.STUBS_RESOURCE));
        originals = new TreeMap<>();
        for (String path : stubs.keySet()) {
            try (var in = StubAttributesTest.class.getClassLoader().getResourceAsStream(path)) {
                originals.put(path, in != null ? in.readAllBytes() : stubs.get(path));
            }
        }
    }

    private static List<String> check(Map<String, byte[]> mutated) throws IOException {
        return StubAttributes.check(originals, mutated, LEDGER_SOURCES);
    }

    private static Map<String, byte[]> without(String path, Predicate<ClassElement> dropped) {
        var mutated = new TreeMap<>(stubs);
        mutated.put(path, ClassFile.of().transformClass(ClassFile.of().parse(stubs.get(path)), ClassTransform.dropping(dropped)));
        return mutated;
    }

    @Test
    void theShippedStubsPass() throws IOException {
        assertEquals(List.of(), check(stubs));
    }

    @Test
    void aDroppedRecordAttributeIsFound() throws IOException {
        var findings = check(without("org/julclang/ledger/TxOut.class", e -> e instanceof RecordAttribute));
        assertTrue(findings.stream().anyMatch(f -> f.startsWith("org/julclang/ledger/TxOut.class: Record is null")), findings.toString());
    }

    @Test
    void aDroppedSignatureIsFound() throws IOException {
        var findings = check(without("org/julclang/core/types/JulcList.class", e -> e instanceof SignatureAttribute));
        assertTrue(findings.stream().anyMatch(f -> f.contains("JulcList.class: Signature is null")), findings.toString());
    }

    @Test
    void aDroppedRuntimeAnnotationIsFound() throws IOException {
        var findings = check(without("org/julclang/stdlib/annotation/NewType.class", e -> e instanceof RuntimeVisibleAnnotationsAttribute));
        assertTrue(findings.stream().anyMatch(f -> f.contains("NewType.class: RuntimeVisibleAnnotations")), findings.toString());
    }

    @Test
    void aDroppedPermittedSubclassesIsFound() throws IOException {
        var findings = check(without("org/julclang/ledger/Credential.class", e -> e instanceof PermittedSubclassesAttribute));
        assertTrue(findings.stream().anyMatch(f -> f.contains("PermittedSubclasses is null")), findings.toString());
    }

    @Test
    void aReorderedSealedLedgerTypeIsFound() throws IOException {
        String path = "org/julclang/ledger/Credential.class";
        var model = ClassFile.of().parse(stubs.get(path));
        var attribute = model.findAttribute(Attributes.permittedSubclasses()).orElseThrow();
        var reversed = attribute.permittedSubclasses().reversed().stream().map(c -> ClassDesc.ofInternalName(c.asInternalName())).toList();
        var mutated = new TreeMap<>(stubs);
        mutated.put(path, ClassFile.of().transformClass(model, ClassTransform.dropping(e -> e instanceof PermittedSubclassesAttribute)
                .andThen(ClassTransform.endHandler(b -> b.with(PermittedSubclassesAttribute.ofSymbols(reversed))))));
        var findings = check(mutated);
        assertTrue(findings.stream().anyMatch(f -> f.contains("source member order is")), findings.toString());
    }

    @Test
    void aMissingStubIsFound() throws IOException {
        var mutated = new TreeMap<>(stubs);
        mutated.remove("org/julclang/ledger/TxOut.class");
        assertFalse(check(mutated).isEmpty());
    }

    @Test
    void noLedgerSourcesIsNotAPass() {
        assertThrows(IOException.class, () -> StubAttributes.check(originals, stubs, Path.of("does-not-exist")));
    }
}
