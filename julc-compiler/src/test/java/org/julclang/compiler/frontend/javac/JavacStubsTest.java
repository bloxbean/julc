package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.LedgerTypeProvider;
import org.julclang.compiler.backend.LibraryType;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-068 I10, I11: the signature-only API stubs and the platform blob, as shipped. */
class JavacStubsTest {

    static final Map<String, byte[]> STUBS = JavacPlatform.read(JavacPlatform.resource(JavacPlatform.STUBS_RESOURCE));
    static final Map<String, byte[]> PLATFORM =
            JavacPlatform.read(JavacPlatform.resource(JavacPlatform.PLATFORM_RESOURCE));
    static final Map<String, LibraryType> LEDGER = new LedgerTypeProvider().types();

    static ClassModel stub(String internalName) {
        byte[] bytes = STUBS.get(internalName + ".class");
        assertNotNull(bytes, internalName + " is not stubbed");
        return ClassFile.of().parse(bytes);
    }

    static String simpleName(String internalName) {
        return internalName.substring(Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$')) + 1);
    }

    /** I11: for every sealed ledger type, PermittedSubclasses order is the JavaParser frontend's variant order. */
    @Test
    void permittedSubclassesOfEverySealedLedgerTypeFollowSourceMemberOrder() {
        var sealed = new TreeMap<String, List<String>>();
        STUBS.forEach((path, bytes) -> {
            if (!path.startsWith("org/julclang/ledger/")) return;
            ClassFile.of().parse(bytes).findAttribute(Attributes.permittedSubclasses()).ifPresent(a -> sealed.put(
                    path.substring(0, path.length() - ".class".length()).replace('/', '.').replace('$', '.'),
                    a.permittedSubclasses().stream().map(c -> simpleName(c.asInternalName())).toList()));
        });
        var frontend = new TreeMap<String, List<String>>();
        LEDGER.forEach((name, type) -> {
            if (!type.constructors().isEmpty()) {
                frontend.put(name, type.constructors().stream().map(LibraryType.Constructor::name).toList());
                for (int i = 0; i < type.constructors().size(); i++) {
                    assertEquals(i, type.constructors().get(i).tag(), name);
                }
            }
        });
        assertEquals(12, sealed.size(), sealed.keySet().toString());
        assertEquals(frontend, sealed);
    }

    /** D8: the empty provider's ledger metadata, read through the real file manager and stubs. */
    @Test
    void ledgerTypesThroughTheMetadataTaskMatchLedgerTypeProvider() {
        JavacFrontend.shared().metadata().read((elements, types) -> {
            check(elements);
            return null;
        });
    }

    private static void check(Elements elements) {
        assertEquals(34, LEDGER.size());
        assertEquals(79, new LedgerTypeProvider().namedDefinitions().size());
        LEDGER.forEach((name, type) -> {
            TypeElement element = elements.getTypeElement(name);
            assertNotNull(element, name);
            if (type.newType()) {
                // A ledger hash type's layout is JuLC's, keyed by FQCN (I11): one component, described as "hash".
                assertEquals(ElementKind.RECORD, element.getKind(), name);
                assertEquals(1, components(element).size(), name);
                return;
            }
            if (type.constructors().isEmpty()) {
                if (!type.fields().isEmpty()) {
                    assertEquals(ElementKind.RECORD, element.getKind(), name);
                    assertEquals(type.fields().stream().map(LibraryType.Field::name).toList(), components(element), name);
                }
                return;
            }
            assertEquals(ElementKind.INTERFACE, element.getKind(), name);
            var permitted = element.getPermittedSubclasses().stream()
                    .map(t -> (TypeElement) ((javax.lang.model.type.DeclaredType) t).asElement()).toList();
            assertEquals(type.constructors().stream().map(LibraryType.Constructor::name).toList(),
                    permitted.stream().map(e -> e.getSimpleName().toString()).toList(), name);
            for (int i = 0; i < permitted.size(); i++) {
                // getRecordComponents() does not complete a class symbol; getTypeElement does (a note for M2).
                var variant = elements.getTypeElement(permitted.get(i).getQualifiedName());
                assertEquals(type.constructors().get(i).fields().stream().map(LibraryType.Field::name).toList(),
                        components(variant), name + "." + variant.getSimpleName());
            }
        });
    }

    static List<String> components(TypeElement record) {
        return record.getRecordComponents().stream().map(c -> c.getSimpleName().toString()).toList();
    }

    /** I11: the stubs keep Record, Signature, runtime-visible annotations and constant values; no code. */
    @Test
    void stubsAreSignatureOnlyAndKeepTheAttributesJavacNeeds() {
        assertTrue(stub("org/julclang/ledger/TxOut").findAttribute(Attributes.record()).isPresent());
        assertTrue(stub("org/julclang/core/types/JulcList").findAttribute(Attributes.signature()).isPresent());
        assertTrue(stub("org/julclang/stdlib/annotation/NewType")
                .findAttribute(Attributes.runtimeVisibleAnnotations()).isPresent());
        FieldModel max = stub("org/julclang/core/ExpModIntegerSemantics").fields().stream()
                .filter(f -> f.fieldName().equalsString("MAX_OPERAND_BITS")).findFirst().orElseThrow();
        assertTrue(max.findAttribute(Attributes.constantValue()).isPresent());
        for (var blob : List.of(STUBS, PLATFORM)) {
            blob.forEach((path, bytes) -> {
                if (path.endsWith("module-info.class")) return;
                ClassModel cm = ClassFile.of().parse(bytes);
                for (MethodModel m : cm.methods()) {
                    assertTrue(m.code().isEmpty(), path + " " + m.methodName() + " keeps code");
                    assertFalse(m.flags().has(AccessFlag.PRIVATE), path + " " + m.methodName() + " is private");
                }
                for (FieldModel f : cm.fields()) {
                    assertFalse(f.flags().has(AccessFlag.PRIVATE), path + " " + f.fieldName() + " is private");
                }
            });
        }
    }

    /** I10: exactly the on-chain API: never an @OnchainLibrary class, never a compiler-facing stdlib class. */
    @Test
    void stubSetIsTheOnChainApi() {
        var packages = new TreeSet<String>();
        STUBS.keySet().forEach(path -> packages.add(path.substring(0, path.lastIndexOf('/'))));
        assertTrue(packages.contains("org/julclang/core"));
        assertTrue(packages.contains("org/julclang/ledger"));
        assertTrue(packages.contains("org/julclang/stdlib/annotation"));
        assertTrue(packages.contains("org/julclang/verification/annotation"));
        assertFalse(packages.contains("org/julclang/stdlib/lib"), packages.toString());
        assertTrue(packages.stream().allMatch(p -> p.startsWith("org/julclang/core") || p.equals("org/julclang/ledger")
                || p.startsWith("org/julclang/stdlib") || p.equals("org/julclang/verification/annotation")),
                packages.toString());
        assertTrue(STUBS.containsKey("org/julclang/stdlib/Builtins.class"));
        assertTrue(STUBS.containsKey("org/julclang/stdlib/test/Test.class"));
        assertTrue(STUBS.containsKey("org/julclang/verification/annotation/RequiresSigner.class"));
        for (String excluded : List.of("StdlibRegistry", "ListsLibHof", "StdlibLibraryProvider")) {
            assertTrue(STUBS.keySet().stream().noneMatch(p -> p.startsWith("org/julclang/stdlib/" + excluded)), excluded);
        }
        assertFalse(STUBS.containsKey("org/julclang/stdlib/test/TestContextLib.class"));
        STUBS.forEach((path, bytes) -> {
            ClassModel cm = ClassFile.of().parse(bytes);
            var annotations = new java.util.ArrayList<java.lang.classfile.Annotation>();
            cm.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> annotations.addAll(a.annotations()));
            cm.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(a -> annotations.addAll(a.annotations()));
            assertTrue(annotations.stream().noneMatch(a -> a.classSymbol().descriptorString().endsWith("/OnchainLibrary;")),
                    path);
        });
    }

    /** D3: the platform is java.base only, with module-info, uncompressed (no Inflater in Web Image). */
    @Test
    void platformIsTheDerivedJavaBaseSubset() {
        assertTrue(PLATFORM.containsKey("java.base/module-info.class"));
        assertTrue(PLATFORM.containsKey("java.base/java/math/BigInteger.class"));
        assertTrue(PLATFORM.keySet().stream().allMatch(p -> p.startsWith("java.base/")));
        byte[] raw = JavacPlatform.resource(JavacPlatform.PLATFORM_RESOURCE);
        assertFalse(raw[0] == 0x1f && (raw[1] & 0xff) == 0x8b, "the blob must not be gzip");
        assertEquals(JavacPlatform.shared().platformClassCount(), PLATFORM.size());
        // Closed under supertypes: every superclass and interface in java.base is present.
        PLATFORM.forEach((path, bytes) -> {
            if (path.endsWith("module-info.class")) return;
            ClassModel cm = ClassFile.of().parse(bytes);
            cm.superclass().ifPresent(s -> assertTrue(PLATFORM.containsKey("java.base/" + s.asInternalName() + ".class"),
                    path + " lacks its superclass " + s.asInternalName()));
            cm.interfaces().forEach(i -> assertTrue(PLATFORM.containsKey("java.base/" + i.asInternalName() + ".class"),
                    path + " lacks its interface " + i.asInternalName()));
        });
    }

    @Test
    void permittedSubclassesAttributeIsKeptOnSealedTypes() {
        var credential = stub("org/julclang/ledger/Credential").findAttribute(Attributes.permittedSubclasses());
        assertEquals(List.of("PubKeyCredential", "ScriptCredential"),
                credential.map(PermittedSubclassesAttribute::permittedSubclasses).orElseThrow().stream()
                        .map(c -> simpleName(c.asInternalName())).toList());
    }
}
