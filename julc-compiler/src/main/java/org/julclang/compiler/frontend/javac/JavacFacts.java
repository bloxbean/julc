package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.core.source.SourceLocation;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one door to javac's attribution (ADR-068 D2): a stateless service over javac's own tree, never a copy of it. It
 * answers tree -> {@link Element}, tree -> {@link TypeMirror}, {@code TypeMirror} -> {@link PirType}, a tree's parent
 * and its position. Queries are keyed by the {@link Tree}; a {@link TreePath} never leaves this class. Identity is
 * nominal (I12): a type is what its {@link TypeElement} says, never what its simple name suggests, so a user type
 * shadows a ledger or built-in name exactly as in Java (#284, #286).
 * <p>
 * One instance serves one javac task, or one query of a {@link JavacMetadata}: model objects must not outlive it.
 */
public final class JavacFacts {

    private static final String PLUTUS_DATA = "org.julclang.core.PlutusData";
    private static final String TYPES = "org.julclang.core.types.";

    /** A fragment's limit, as the JavaParser frontend's source positions have it. */
    private static final int FRAGMENT_LIMIT = 100;

    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final TypeRegistry registry;
    /** Every tree of the units javac read, to its path. Empty over a task with no units. */
    private final Map<Tree, TreePath> paths = new IdentityHashMap<>();

    private JavacFacts(Trees trees, Elements elements, Types types, TypeRegistry registry) {
        this.trees = trees;
        this.elements = elements;
        this.types = types;
        this.registry = registry;
    }

    /** Facts over an attributed task. */
    static JavacFacts of(JavacFrontend.Attribution attribution, TypeRegistry registry) {
        var facts = new JavacFacts(attribution.trees(), attribution.elements(), attribution.types(), registry);
        attribution.roots().forEach(facts::index);
        attribution.libraries().forEach(facts::index);
        return facts;
    }

    /**
     * Records every tree's path, once per unit. javac shares a few subtrees between a record's field and its generated
     * members; such a tree keeps its first path, and any of them gives the same element, type and position.
     */
    private void index(CompilationUnitTree unit) {
        new TreeScanner<Void, TreePath>() {
            @Override
            public Void scan(Tree tree, TreePath parent) {
                if (tree == null) return null;
                var path = new TreePath(parent, tree);
                paths.putIfAbsent(tree, path);
                return tree.accept(this, path);
            }
        }.scan(unit, null);
    }

    /** Facts over a task with no compilation units: its stubs have no trees. */
    static JavacFacts of(Elements elements, Types types, TypeRegistry registry) {
        return new JavacFacts(null, elements, types, registry);
    }

    public Elements elements() {
        return elements;
    }

    public Types types() {
        return types;
    }

    /** The registries types resolve against. */
    public TypeRegistry registry() {
        return registry;
    }

    /** The path of {@code tree}: one that is in no unit javac read is an internal error (I12). */
    private TreePath path(Tree tree) {
        TreePath path = paths.get(tree);
        if (path == null) throw new IllegalStateException("not a tree of a unit javac read: " + tree.getKind());
        return path;
    }

    /** The element {@code tree} denotes; a tree of a kind with no element is a caller's error (I12). */
    public Element element(Tree tree) {
        Element element = trees.getElement(path(tree));
        if (element == null) throw new IllegalStateException("javac attributed no element to " + tree.getKind());
        return element;
    }

    /** The attributed type of {@code tree}; an error type never reaches JuLC (I12). */
    public TypeMirror type(Tree tree) {
        TypeMirror type = trees.getTypeMirror(path(tree));
        if (type == null || type.getKind() == TypeKind.ERROR) {
            throw new IllegalStateException("javac attributed no type to " + tree.getKind());
        }
        return type;
    }

    /** The tree that contains {@code tree}, or null for a compilation unit. */
    public Tree parent(Tree tree) {
        TreePath parent = path(tree).getParentPath();
        return parent == null ? null : parent.getLeaf();
    }

    /** The nearest tree around {@code tree} of one of {@code kinds}, or null. */
    public Tree enclosing(Tree tree, Tree.Kind... kinds) {
        var wanted = EnumSet.copyOf(List.of(kinds));
        for (TreePath path = path(tree).getParentPath(); path != null; path = path.getParentPath()) {
            if (wanted.contains(path.getLeaf().getKind())) return path.getLeaf();
        }
        return null;
    }

    /** The class a declared type names. */
    public TypeElement typeElement(TypeMirror type) {
        return (TypeElement) types.asElement(type);
    }

    /** A record's components. Reading them does not complete a class by itself, so this does. */
    public List<? extends RecordComponentElement> components(TypeElement record) {
        record.getKind();
        return record.getRecordComponents();
    }

    /** A sealed type's permitted subclasses, in order, completed; an unresolved one never reaches JuLC (I12). */
    public List<TypeElement> permitted(TypeElement sealed) {
        sealed.getKind();
        var permitted = new ArrayList<TypeElement>();
        for (TypeMirror type : sealed.getPermittedSubclasses()) {
            if (type.getKind() == TypeKind.ERROR) throw new IllegalStateException("an error type reached JuLC: " + type);
            permitted.add(typeElement(type));
        }
        return permitted;
    }

    /** Whether {@code type} carries the annotation named {@code annotation}, by identity. */
    public boolean hasAnnotation(TypeElement type, String annotation) {
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            if (identity((TypeElement) mirror.getAnnotationType().asElement()).equals(annotation)) return true;
        }
        return false;
    }

    /**
     * A type's identity, the key of every registry: its qualified name. Java gives a local type none, and javac reports
     * its simple name there, which could be anything's: so a local type, and a type nested in one, is its binary name
     * ({@code P$1R}), which javac keeps unique. That is not the JavaParser frontend's key, the bare simple name.
     */
    public String identity(TypeElement type) {
        for (Element e = type; e instanceof TypeElement || e instanceof ExecutableElement || e instanceof VariableElement;
             e = e.getEnclosingElement()) {
            if (e instanceof TypeElement t && (t.getNestingKind() == NestingKind.LOCAL
                    || t.getNestingKind() == NestingKind.ANONYMOUS)) {
                return elements.getBinaryName(type).toString();
            }
        }
        return type.getQualifiedName().toString();
    }

    /**
     * The PIR type of {@code type} (ADR-068 D7): built-in types by identity, then the registry by identity, and nothing
     * by simple name. A rejection is the JavaParser frontend's, with its names as javac prints them, located at
     * {@code where}.
     *
     * @param where the tree that writes {@code type}, or null for a stub, which has none
     */
    public PirType pirType(TypeMirror type, Tree where) {
        return switch (type.getKind()) {
            case BOOLEAN -> new PirType.BoolType();
            case INT, LONG -> new PirType.IntegerType();
            case VOID -> new PirType.UnitType();
            case BYTE, SHORT, CHAR, FLOAT, DOUBLE -> throw rejection("Unsupported primitive type: " + type
                    + ". Supported types: boolean, int, long", where);
            case ARRAY -> array((ArrayType) type, where);
            case DECLARED -> declared((DeclaredType) type, where);
            case TYPEVAR -> throw rejection(registry.unknownTypeMessage(type.toString(), type.toString()), where);
            case ERROR -> throw new IllegalStateException("an error type reached JuLC: " + type);
            default -> throw rejection("Cannot resolve type: " + type + ". Supported types: BigInteger,"
                    + " String, boolean, byte[], List<T>, Map<K,V>, records, sealed interfaces", where);
        };
    }

    /** A rejection at {@code where}. */
    CompilerException rejection(String message, Tree where) {
        if (where == null) return new CompilerException(message);
        var at = location(where);
        return new CompilerException(List.of(new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, message,
                at.fileName(), at.line(), at.column())));
    }

    private PirType array(ArrayType type, Tree where) {
        if (type.getComponentType().getKind() == TypeKind.BYTE) return new PirType.ByteStringType();
        throw rejection("Arrays are not supported except byte[]. Use List<T> instead. Got: " + type, where);
    }

    private PirType declared(DeclaredType type, Tree where) {
        var element = (TypeElement) type.asElement();
        String identity = identity(element);
        var arguments = type.getTypeArguments();
        String simple = element.getSimpleName().toString();
        return switch (identity) {
            case "java.math.BigInteger" -> new PirType.IntegerType();
            case "java.lang.String" -> new PirType.StringType();
            case "java.lang.Boolean" -> new PirType.BoolType();
            case PLUTUS_DATA, PLUTUS_DATA + ".ConstrData", PLUTUS_DATA + ".MapData", PLUTUS_DATA + ".ListData",
                 PLUTUS_DATA + ".IntData", PLUTUS_DATA + ".BytesData" -> new PirType.DataType();
            case TYPES + "JulcValue" -> new PirType.NativeValueType();
            case TYPES + "JulcG1" -> new PirType.NativeG1Type();
            case TYPES + "JulcG2" -> new PirType.NativeG2Type();
            case TYPES + "JulcMlResult" -> new PirType.NativeMlResultType();
            case TYPES + "JulcScalars" -> new PirType.NativeListType(new PirType.IntegerType());
            case TYPES + "JulcG1Points" -> new PirType.NativeListType(new PirType.NativeG1Type());
            case TYPES + "JulcG2Points" -> new PirType.NativeListType(new PirType.NativeG2Type());
            case "java.util.List", TYPES + "JulcList" -> new PirType.ListType(argument(arguments, 0, simple, where));
            case TYPES + "JulcArray" -> new PirType.ArrayType(argument(arguments, 0, simple, where));
            case "java.util.Map", TYPES + "JulcMap" -> new PirType.MapType(argument(arguments, 0, simple, where),
                    argument(arguments, 1, simple, where));
            case TYPES + "Tuple2" -> new PirType.RecordType("Tuple2", List.of(
                    new PirType.Field("first", argument(arguments, 0, simple, where)),
                    new PirType.Field("second", argument(arguments, 1, simple, where))));
            case TYPES + "Tuple3" -> new PirType.RecordType("Tuple3", List.of(
                    new PirType.Field("first", argument(arguments, 0, simple, where)),
                    new PirType.Field("second", argument(arguments, 1, simple, where)),
                    new PirType.Field("third", argument(arguments, 2, simple, where))));
            case TYPES + "AssetEntry" -> new PirType.RecordType("AssetEntry", List.of(
                    new PirType.Field("policyId", new PirType.ByteStringType()),
                    new PirType.Field("tokenName", new PirType.ByteStringType()),
                    new PirType.Field("amount", new PirType.IntegerType())));
            case "java.util.Optional" -> new PirType.OptionalType(argument(arguments, 0, simple, where));
            default -> registry.lookupNominal(identity)
                    .orElseThrow(() -> rejection(registry.unknownTypeMessage(simple, identity), where));
        };
    }

    /** A Data-encoded container's type argument, or Data when raw: {@code JULC0091} if it is a native array. */
    private PirType argument(List<? extends TypeMirror> arguments, int index, String container, Tree where) {
        if (arguments.isEmpty()) return new PirType.DataType();
        PirType type = pirType(arguments.get(index), where);
        var array = PirType.nativeArrayIn(type);
        if (array != null) {
            throw CompilerTypeDiagnostics.nativeArrayNotDataEncodable("A type argument of " + container, array,
                    where == null ? null : location(where));
        }
        return type;
    }

    /**
     * The source position of {@code tree}: line from javac's {@link LineMap}, column a character offset (I7), and the
     * fragment of the source it spans. The fragment is the one provenance rule (ADR-068 D13): the text as written, with
     * each run of white space one space, and past {@value #FRAGMENT_LIMIT} characters cut to 97 and {@code ...}, as the
     * JavaParser frontend cuts its own. A tree javac generated has no text, and so an empty fragment.
     */
    public SourceLocation location(Tree tree) {
        var unit = path(tree).getCompilationUnit();
        var positions = trees.getSourcePositions();
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        String file = fileName(unit);
        if (start < 0) return new SourceLocation(file, 0, 0, "");
        LineMap lines = unit.getLineMap();
        long line = lines.getLineNumber(start);
        return new SourceLocation(file, (int) line, (int) (start - lines.getStartPosition(line) + 1),
                end < 0 ? "" : fragment(unit, start, end));
    }

    private static String fragment(CompilationUnitTree unit, long start, long end) {
        CharSequence source;
        try {
            source = unit.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String text = source.subSequence((int) start, (int) end).toString().strip().replaceAll("\\s+", " ");
        return text.length() > FRAGMENT_LIMIT ? text.substring(0, FRAGMENT_LIMIT - 3) + "..." : text;
    }

    /** The start offset of {@code tree} in its unit, or -1. */
    long start(Tree tree) {
        return trees.getSourcePositions().getStartPosition(path(tree).getCompilationUnit(), tree);
    }

    /** The end offset of {@code tree} in its unit, or -1 for a tree javac generated. */
    long end(Tree tree) {
        return trees.getSourcePositions().getEndPosition(path(tree).getCompilationUnit(), tree);
    }

    private static String fileName(CompilationUnitTree unit) {
        var source = unit.getSourceFile();
        String name = source instanceof JavacSourceFile file ? file.displayName() : source.getName();
        return name.substring(name.lastIndexOf('/') + 1);
    }
}
