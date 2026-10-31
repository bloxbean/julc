package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.core.source.SourceLocation;

import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;

/**
 * The one door to javac's attribution (ADR-068 D2): a service over javac's own tree, never a copy of it. It answers
 * tree -> {@link Element}, tree -> {@link TypeMirror}, {@code TypeMirror} -> {@link PirType} and positions. Identity is
 * nominal (I12): a type is what its {@link TypeElement}'s qualified name says, never what its simple name suggests, so
 * a user type shadows a ledger or built-in name exactly as in Java (#284, #286).
 * <p>
 * One instance serves one javac task, or one query of a {@link JavacMetadata}: model objects must not outlive it.
 */
public final class JavacFacts {

    private static final String PLUTUS_DATA = "org.julclang.core.PlutusData";
    private static final String TYPES = "org.julclang.core.types.";

    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final TypeRegistry registry;

    private JavacFacts(Trees trees, Elements elements, Types types, TypeRegistry registry) {
        this.trees = trees;
        this.elements = elements;
        this.types = types;
        this.registry = registry;
    }

    /** Facts over an attributed task. */
    static JavacFacts of(JavacFrontend.Attribution attribution, TypeRegistry registry) {
        return new JavacFacts(attribution.trees(), attribution.elements(), attribution.types(), registry);
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

    /** javac's trees, for the frontend's own use. */
    Trees trees() {
        return trees;
    }

    /** The registries types resolve against. */
    public TypeRegistry registry() {
        return registry;
    }

    /** The element {@code path} denotes; a tree of a kind with no element is a caller's error (I12). */
    public Element element(TreePath path) {
        Element element = trees.getElement(path);
        if (element == null) {
            throw new IllegalStateException("javac attributed no element to " + path.getLeaf().getKind());
        }
        return element;
    }

    /** The attributed type of {@code path}; an error type never reaches JuLC (I12). */
    public TypeMirror type(TreePath path) {
        TypeMirror type = trees.getTypeMirror(path);
        if (type == null || type.getKind() == TypeKind.ERROR) {
            throw new IllegalStateException("javac attributed no type to " + path.getLeaf().getKind());
        }
        return type;
    }

    /** The class a declared type names. */
    public TypeElement typeElement(TypeMirror type) {
        return (TypeElement) types.asElement(type);
    }

    /**
     * A type's identity: its qualified name, the key of every registry. A local type has none in Java, so it, and a
     * type nested in it, is its enclosing type's identity and its simple name, as the source path names it.
     */
    public static String identity(TypeElement type) {
        if (type.getNestingKind() == NestingKind.TOP_LEVEL) return type.getQualifiedName().toString();
        Element enclosing = type.getEnclosingElement();
        while (!(enclosing instanceof TypeElement) || enclosing.getSimpleName().isEmpty()) {
            enclosing = enclosing.getEnclosingElement();
        }
        return identity((TypeElement) enclosing) + "." + type.getSimpleName();
    }

    /**
     * The PIR type of {@code type} (ADR-068 D7): built-in types by identity, then the registries by identity, and
     * nothing by simple name. A rejection has the JavaParser frontend's text, with its names as javac prints them.
     *
     * @param where where {@code type} is written, for {@code JULC0091}, or null
     */
    public PirType pirType(TypeMirror type, SourceLocation where) {
        return switch (type.getKind()) {
            case BOOLEAN -> new PirType.BoolType();
            case INT, LONG -> new PirType.IntegerType();
            case VOID -> new PirType.UnitType();
            case BYTE, SHORT, CHAR, FLOAT, DOUBLE -> throw new CompilerException("Unsupported primitive type: " + type
                    + ". Supported types: boolean, int, long");
            case ARRAY -> array((ArrayType) type);
            case DECLARED -> declared((DeclaredType) type, where);
            case TYPEVAR -> throw new CompilerException(registry.unknownTypeMessage(type.toString(), type.toString()));
            case ERROR -> throw new IllegalStateException("an error type reached JuLC: " + type);
            default -> throw new CompilerException("Cannot resolve type: " + type + ". Supported types: BigInteger,"
                    + " String, boolean, byte[], List<T>, Map<K,V>, records, sealed interfaces");
        };
    }

    private static PirType array(ArrayType type) {
        if (type.getComponentType().getKind() == TypeKind.BYTE) return new PirType.ByteStringType();
        throw new CompilerException("Arrays are not supported except byte[]. Use List<T> instead. Got: " + type);
    }

    private PirType declared(DeclaredType type, SourceLocation where) {
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
            default -> registry.lookupNominal(identity).orElseThrow(
                    () -> new CompilerException(registry.unknownTypeMessage(simple, identity)));
        };
    }

    /** A Data-encoded container's type argument, or Data when raw: {@code JULC0091} if it is a native array. */
    private PirType argument(List<? extends TypeMirror> arguments, int index, String container, SourceLocation where) {
        if (arguments.isEmpty()) return new PirType.DataType();
        PirType type = pirType(arguments.get(index), where);
        var array = PirType.nativeArrayIn(type);
        if (array != null) {
            throw CompilerTypeDiagnostics.nativeArrayNotDataEncodable("A type argument of " + container, array, where);
        }
        return type;
    }

    /** The source position of {@code tree}: line from javac's {@link LineMap}, column a character offset (I7). */
    public SourceLocation location(CompilationUnitTree unit, Tree tree) {
        long start = trees.getSourcePositions().getStartPosition(unit, tree);
        String file = fileName(unit);
        if (start < 0) return new SourceLocation(file, 0, 0, tree.toString());
        LineMap lines = unit.getLineMap();
        long line = lines.getLineNumber(start);
        return new SourceLocation(file, (int) line, (int) (start - lines.getStartPosition(line) + 1), tree.toString());
    }

    private static String fileName(CompilationUnitTree unit) {
        var source = unit.getSourceFile();
        String name = source instanceof JavacSourceFile file ? file.displayName() : source.getName();
        return name.substring(name.lastIndexOf('/') + 1);
    }
}
