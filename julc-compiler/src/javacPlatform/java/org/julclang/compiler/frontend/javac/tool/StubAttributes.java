package org.julclang.compiler.frontend.javac.tool;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;

import javax.lang.model.element.Modifier;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * ADR-068 I11's check of the API stubs, run by the generator: a blob that fails it is not written. A stub keeps its
 * class's {@code Record}, {@code Signature}, {@code PermittedSubclasses} and runtime-visible annotations, on the class
 * and on every member it keeps; and every sealed ledger type's {@code PermittedSubclasses} is in the source order the
 * JavaParser frontend gives its tags. The expected order comes from javac's parser over the ledger's sources, with no
 * attribution, so nothing that produced the attribute checks it.
 */
final class StubAttributes {

    private static final String LEDGER = "org/julclang/ledger/";

    private StubAttributes() {
    }

    /**
     * The check's findings: empty when the stubs are good.
     *
     * @param originals      class path -> the compiled class
     * @param stubs          class path -> its stub
     * @param ledgerSources  the source root of {@code org.julclang.ledger}
     */
    static List<String> check(Map<String, byte[]> originals, Map<String, byte[]> stubs, Path ledgerSources)
            throws IOException {
        var problems = new ArrayList<String>();
        if (!originals.keySet().equals(stubs.keySet())) {
            problems.add("the stubs are not the inputs: " + difference(originals, stubs));
        }
        stubs.forEach((path, stub) -> {
            if (originals.containsKey(path)) compare(path, ClassFile.of().parse(originals.get(path)), ClassFile.of().parse(stub), problems);
        });
        var expected = sourceOrder(ledgerSources);
        var actual = new TreeMap<String, List<String>>();
        stubs.forEach((path, stub) -> {
            if (path.startsWith(LEDGER)) {
                ClassFile.of().parse(stub).findAttribute(Attributes.permittedSubclasses()).ifPresent(a -> actual.put(path,
                        a.permittedSubclasses().stream().map(c -> c.asInternalName() + ".class").toList()));
            }
        });
        if (expected.isEmpty()) problems.add("no sealed interface found in the ledger sources at " + ledgerSources);
        if (!expected.keySet().equals(actual.keySet())) {
            problems.add("sealed ledger types: sources " + expected.keySet() + ", stubs " + actual.keySet());
        }
        expected.forEach((path, order) -> {
            if (actual.containsKey(path) && !order.equals(actual.get(path))) {
                problems.add(path + ": PermittedSubclasses is " + actual.get(path) + ", source member order is " + order);
            }
        });
        return problems;
    }

    private static String difference(Map<String, byte[]> originals, Map<String, byte[]> stubs) {
        var missing = new TreeMap<>(originals);
        missing.keySet().removeAll(stubs.keySet());
        var extra = new TreeMap<>(stubs);
        extra.keySet().removeAll(originals.keySet());
        return "missing " + missing.keySet() + ", extra " + extra.keySet();
    }

    private static void compare(String path, ClassModel original, ClassModel stub, List<String> problems) {
        compareElement(path, "", original, stub, problems);
        var recordOriginal = original.findAttribute(Attributes.record()).map(a -> components(a.components())).orElse(null);
        var recordStub = stub.findAttribute(Attributes.record()).map(a -> components(a.components())).orElse(null);
        if (!java.util.Objects.equals(recordOriginal, recordStub)) {
            problems.add(path + ": Record is " + recordStub + ", expected " + recordOriginal);
        }
        var permittedOriginal = original.findAttribute(Attributes.permittedSubclasses())
                .map(a -> a.permittedSubclasses().stream().map(c -> c.asInternalName()).toList()).orElse(null);
        var permittedStub = stub.findAttribute(Attributes.permittedSubclasses())
                .map(a -> a.permittedSubclasses().stream().map(c -> c.asInternalName()).toList()).orElse(null);
        if (!java.util.Objects.equals(permittedOriginal, permittedStub)) {
            problems.add(path + ": PermittedSubclasses is " + permittedStub + ", expected " + permittedOriginal);
        }
        var stubFields = new LinkedHashMap<String, FieldModel>();
        stub.fields().forEach(f -> stubFields.put(f.fieldName().stringValue() + f.fieldType().stringValue(), f));
        for (FieldModel field : original.fields()) {
            String key = field.fieldName().stringValue() + field.fieldType().stringValue();
            if (field.flags().has(AccessFlag.PRIVATE)) continue;
            if (!stubFields.containsKey(key)) problems.add(path + ": the field " + key + " is missing");
            else compareElement(path, " field " + key, field, stubFields.get(key), problems);
        }
        var stubMethods = new LinkedHashMap<String, MethodModel>();
        stub.methods().forEach(m -> stubMethods.put(m.methodName().stringValue() + m.methodType().stringValue(), m));
        for (MethodModel method : original.methods()) {
            String key = method.methodName().stringValue() + method.methodType().stringValue();
            if (method.flags().has(AccessFlag.PRIVATE)) continue;
            if (!stubMethods.containsKey(key)) problems.add(path + ": the method " + key + " is missing");
            else compareElement(path, " method " + key, method, stubMethods.get(key), problems);
        }
    }

    private static void compareElement(String path, String where, AttributedElement original, AttributedElement stub,
                                       List<String> problems) {
        var signature = signature(original);
        if (!java.util.Objects.equals(signature, signature(stub))) {
            problems.add(path + where + ": Signature is " + signature(stub) + ", expected " + signature);
        }
        var annotations = annotations(original);
        if (!annotations.equals(annotations(stub))) {
            problems.add(path + where + ": RuntimeVisibleAnnotations is " + annotations(stub) + ", expected " + annotations);
        }
    }

    private static String signature(AttributedElement element) {
        return element.findAttribute(Attributes.signature()).map(a -> a.signature().stringValue()).orElse(null);
    }

    private static List<String> annotations(AttributedElement element) {
        return element.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(a -> a.annotations().stream().map(StubAttributes::describe).toList()).orElse(List.of());
    }

    private static List<String> components(List<RecordComponentInfo> components) {
        return components.stream().map(c -> c.name().stringValue() + " " + c.descriptor().stringValue() + " "
                + signature(c) + " " + annotations(c)).toList();
    }

    private static String describe(Annotation annotation) {
        var text = new StringBuilder(annotation.className().stringValue()).append('(');
        annotation.elements().forEach(e -> text.append(e.name().stringValue()).append('=').append(describe(e.value())).append(','));
        return text.append(')').toString();
    }

    private static String describe(AnnotationValue value) {
        return switch (value) {
            case AnnotationValue.OfConstant constant -> constant.tag() + ":" + constant.resolvedValue();
            case AnnotationValue.OfClass type -> "class " + type.className().stringValue();
            case AnnotationValue.OfEnum constant -> constant.className().stringValue() + "." + constant.constantName().stringValue();
            case AnnotationValue.OfAnnotation nested -> describe(nested.annotation());
            case AnnotationValue.OfArray array -> array.values().stream().map(StubAttributes::describe).toList().toString();
        };
    }

    /** Sealed interface class path -> its variants' class paths: its {@code permits}, else its nested records, in order. */
    private static Map<String, List<String>> sourceOrder(Path root) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var result = new TreeMap<String, List<String>>();
        try (var fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
             Stream<Path> files = Files.list(root.resolve(LEDGER))) {
            var sources = fileManager.getJavaFileObjectsFromPaths(files.filter(p -> p.toString().endsWith(".java")).sorted().toList());
            var task = (JavacTask) compiler.getTask(null, fileManager, diagnostic -> { }, List.of("-proc:none"), null, sources);
            for (CompilationUnitTree unit : task.parse()) {
                for (Tree type : unit.getTypeDecls()) {
                    if (type instanceof ClassTree declaration) collect(declaration, LEDGER + declaration.getSimpleName(), result);
                }
            }
        }
        return result;
    }

    private static void collect(ClassTree type, String binary, Map<String, List<String>> result) {
        if (type.getKind() == Tree.Kind.INTERFACE && type.getModifiers().getFlags().contains(Modifier.SEALED)) {
            var variants = new ArrayList<String>();
            if (type.getPermitsClause().isEmpty()) {
                for (Tree member : type.getMembers()) {
                    if (member instanceof ClassTree record && record.getKind() == Tree.Kind.RECORD
                            && record.getImplementsClause().stream().anyMatch(t -> t.toString().equals(type.getSimpleName().toString()))) {
                        variants.add(binary + "$" + record.getSimpleName() + ".class");
                    }
                }
            } else {
                type.getPermitsClause().forEach(t -> variants.add(LEDGER + t.toString().replace('.', '$') + ".class"));
            }
            result.put(binary + ".class", variants);
        }
        for (Tree member : type.getMembers()) {
            if (member instanceof ClassTree nested) collect(nested, binary + "$" + nested.getSimpleName(), result);
        }
    }
}
