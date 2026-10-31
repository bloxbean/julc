package org.julclang.difftest;

import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.LedgerLayouts;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.corpus.CorpusFiles;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ADR-068 M2 (I11): one sample value of a type, and its Data computed from a registry alone.
 * <p>
 * A {@link Sample} is built from javac's model, for a type's names; every leaf has a value of its own, so a field out
 * of place changes the Data. {@link #encode} is hand-written against JuLC's Data rules. It reads tags, field order and
 * field types only from the {@link Layout} under test, and checks at each position that the sample fits the type the
 * registry gives there, so a retyped field fails. Its result is compared with the frozen reference, which comes from
 * elsewhere: a program the JavaParser frontend compiled, evaluated on the VM ({@code Adr068EncodingsTest}), or the
 * ledger's own {@code toPlutusData}. So a wrong tag, order or field type in the registry fails.
 */
final class Adr068Encodings {

    /** A sample value. */
    sealed interface Sample {
        /** The Java expression that builds it. */
        String java();
    }

    record IntegerSample(BigInteger value, String java) implements Sample { }
    record BytesSample(byte[] value, String java) implements Sample { }
    record StringSample(String value, String java) implements Sample { }
    record BoolSample(boolean value, String java) implements Sample { }
    /** A {@code PlutusData} value: an {@code IntData}. */
    record DataSample(BigInteger value, String java) implements Sample { }
    record ListSample(List<Sample> items, String java) implements Sample { }
    record MapSample(Sample key, Sample value, String java) implements Sample { }
    record OptionalSample(Sample value, String java) implements Sample { }
    /** A record or variant, by identity: its components by name. */
    record RecordSample(String identity, List<String> names, List<Sample> fields, String java) implements Sample { }
    /** A {@code @NewType} value, which is its underlying value. */
    record NewTypeSample(Sample value, String java) implements Sample { }

    /**
     * What decides a value's Data: the registry, and the records whose Data is not a constructor. A frontend's reading
     * of both is what is under test, so each caller passes the one it read.
     */
    record Layout(TypeRegistry.Snapshot registry, Set<String> notConstructorData) {
    }

    /** A sample cannot be built: the message says why. */
    static final class NoSample extends RuntimeException {
        NoSample(String reason) {
            super(reason, null, false, false);
        }
    }

    private Adr068Encodings() {
    }

    /** Builds samples of a program's types; one instance numbers its leaves from 1. */
    static final class Sampler {
        private static final int DEPTH = 6;
        private static final String TYPES = "org.julclang.core.types.";
        private final JavacFacts facts;
        private final TypeRegistry.Snapshot registry;
        private int next = 1;

        Sampler(JavacFacts facts) {
            this.facts = facts;
            this.registry = facts.registry().snapshot();
        }

        private int leaf() {
            return next++;
        }

        /** A sample of {@code type}. Throws {@link NoSample}. */
        Sample of(TypeMirror type, int depth) {
            if (depth >= DEPTH - 1) {
                // A recursive type bottoms out in an empty container.
                String identity = type instanceof DeclaredType declared ? facts.identity((TypeElement) declared.asElement()) : "";
                if (identity.equals(TYPES + "JulcList") || identity.equals("java.util.List")) {
                    return new ListSample(List.of(), "JulcList.empty()");
                }
                if (identity.equals("java.util.Optional")) return new OptionalSample(null, "Optional.empty()");
            }
            if (depth > DEPTH) throw new NoSample("deeper than " + DEPTH);
            int n = leaf();
            return switch (type.getKind()) {
                case BOOLEAN -> new BoolSample(n % 2 == 0, String.valueOf(n % 2 == 0));
                case INT -> new IntegerSample(BigInteger.valueOf(n), String.valueOf(n));
                case LONG -> new IntegerSample(BigInteger.valueOf(n), n + "L");
                case ARRAY -> {
                    if (((ArrayType) type).getComponentType().getKind() != TypeKind.BYTE) throw new NoSample("array " + type);
                    yield new BytesSample(new byte[] {(byte) (n & 0x7f), (byte) (n >> 7 & 0x7f)},
                            "new byte[] {" + (n & 0x7f) + ", " + (n >> 7 & 0x7f) + "}");
                }
                case DECLARED -> declared((DeclaredType) type, n, depth);
                default -> throw new NoSample("type " + type);
            };
        }

        private Sample declared(DeclaredType type, int n, int depth) {
            var element = (TypeElement) type.asElement();
            String identity = facts.identity(element);
            var arguments = type.getTypeArguments();
            return switch (identity) {
                case "java.math.BigInteger" -> new IntegerSample(BigInteger.valueOf(n), "BigInteger.valueOf(" + n + ")");
                case "java.lang.String" -> new StringSample("s" + n, "\"s" + n + "\"");
                case "java.lang.Boolean" -> new BoolSample(n % 2 == 0, String.valueOf(n % 2 == 0));
                case "org.julclang.core.PlutusData" -> new DataSample(BigInteger.valueOf(n),
                        "Builtins.iData(BigInteger.valueOf(" + n + "))");
                case "java.util.List", TYPES + "JulcList" -> {
                    if (arguments.isEmpty()) throw new NoSample("raw " + identity);
                    var item = of(arguments.getFirst(), depth + 1);
                    yield new ListSample(List.of(item), "JulcList.of(" + item.java() + ")");
                }
                case "java.util.Map", TYPES + "JulcMap" -> {
                    if (arguments.isEmpty()) throw new NoSample("raw " + identity);
                    var key = of(arguments.get(0), depth + 1);
                    var value = of(arguments.get(1), depth + 1);
                    yield new MapSample(key, value, "JulcMap.<" + arguments.get(0) + ", " + arguments.get(1)
                            + ">empty().insert(" + key.java() + ", " + value.java() + ")");
                }
                case "java.util.Optional" -> {
                    if (arguments.isEmpty()) throw new NoSample("raw " + identity);
                    var value = of(arguments.getFirst(), depth + 1);
                    yield new OptionalSample(value, "Optional.of(" + value.java() + ")");
                }
                case TYPES + "Tuple2", TYPES + "Tuple3" -> {
                    if (arguments.isEmpty()) throw new NoSample("raw " + identity);
                    var names = List.of("first", "second", "third").subList(0, arguments.size());
                    var fields = new ArrayList<Sample>();
                    for (TypeMirror argument : arguments) fields.add(of(argument, depth + 1));
                    yield new RecordSample(identity, names, fields, "new " + type + "("
                            + String.join(", ", fields.stream().map(Sample::java).toList()) + ")");
                }
                case TYPES + "AssetEntry" -> record(element, depth + 1);
                case LedgerLayouts.PACKAGE + ".Value" -> {
                    var none = new BytesSample(new byte[0], "");
                    var tokens = new MapSample(none, new IntegerSample(BigInteger.valueOf(n), ""), "");
                    yield new RecordSample(identity, List.of("inner"), List.of(new MapSample(none, tokens, "")),
                            "Value.lovelace(BigInteger.valueOf(" + n + "))");
                }
                default -> {
                    if (TypeRegistry.ledgerHashFqcns().contains(identity)) {
                        var bytes = (BytesSample) of(arrayOfBytes(), depth + 1);
                        yield new BytesSample(bytes.value(), "new " + identity + "(" + bytes.java() + ")");
                    }
                    yield named(element, identity, depth);
                }
            };
        }

        private TypeMirror arrayOfBytes() {
            return facts.types().getArrayType(facts.types().getPrimitiveType(TypeKind.BYTE));
        }

        private Sample named(TypeElement element, String identity, int depth) {
            if (registry.newTypes().containsKey(identity)) {
                var value = of(facts.components(element).getFirst().asType(), depth + 1);
                return new NewTypeSample(value, "new " + identity + "(" + value.java() + ")");
            }
            if (registry.sums().containsKey(identity)) {
                NoSample failure = null;
                for (var variant : facts.permitted(element)) {
                    try {
                        return record(variant, depth + 1);
                    } catch (NoSample e) {
                        failure = e;
                    }
                }
                throw failure != null ? failure : new NoSample("sealed interface with no variant " + identity);
            }
            if (registry.records().containsKey(identity)) return record(element, depth + 1);
            throw new NoSample("no sample of " + identity);
        }

        /** A sample of a record, or of a sealed interface's variant. */
        RecordSample record(TypeElement record, int depth) {
            var names = new ArrayList<String>();
            var fields = new ArrayList<Sample>();
            for (var component : facts.components(record)) {
                names.add(component.getSimpleName().toString());
                fields.add(of(component.asType(), depth));
            }
            String identity = facts.identity(record);
            var arguments = String.join(", ", fields.stream().map(Sample::java).toList());
            return new RecordSample(identity, names, fields, "new " + identity + "(" + arguments + ")");
        }

        /** The value of a {@code @NewType}: {@code new X(v)} is {@code v}. */
        Sample newType(TypeElement element) {
            return named(element, facts.identity(element), 0);
        }
    }

    /** The Data of {@code sample} at {@code type}, reading tags, order and field types from {@code layout} alone. */
    static PlutusData encode(Sample sample, PirType type, Layout layout) {
        PirType resolved = type instanceof PirType.NamedTypeRef ref ? named(ref, layout.registry()) : type;
        return switch (sample) {
            case IntegerSample s -> {
                expect(resolved, PirType.IntegerType.class, sample);
                yield new PlutusData.IntData(s.value());
            }
            case BytesSample s -> {
                expect(resolved, PirType.ByteStringType.class, sample);
                yield new PlutusData.BytesData(s.value());
            }
            case StringSample s -> {
                expect(resolved, PirType.StringType.class, sample);
                yield new PlutusData.BytesData(s.value().getBytes(StandardCharsets.UTF_8));
            }
            case BoolSample s -> {
                expect(resolved, PirType.BoolType.class, sample);
                yield new PlutusData.ConstrData(s.value() ? 1 : 0, List.of());
            }
            case DataSample s -> {
                expect(resolved, PirType.DataType.class, sample);
                yield new PlutusData.IntData(s.value());
            }
            case NewTypeSample s -> encode(s.value(), resolved, layout);
            case ListSample s -> {
                expect(resolved, PirType.ListType.class, sample);
                yield new PlutusData.ListData(s.items().stream()
                        .map(item -> encode(item, ((PirType.ListType) resolved).elemType(), layout)).toList());
            }
            case MapSample s -> {
                expect(resolved, PirType.MapType.class, sample);
                var map = (PirType.MapType) resolved;
                yield new PlutusData.MapData(List.of(new PlutusData.Pair(encode(s.key(), map.keyType(), layout),
                        encode(s.value(), map.valueType(), layout))));
            }
            case OptionalSample s -> {
                expect(resolved, PirType.OptionalType.class, sample);
                yield s.value() == null ? new PlutusData.ConstrData(1, List.of())
                        : new PlutusData.ConstrData(0, List.of(encode(s.value(), ((PirType.OptionalType) resolved).elemType(), layout)));
            }
            case RecordSample s -> record(s, resolved, layout);
        };
    }

    private static void expect(PirType type, Class<? extends PirType> kind, Sample sample) {
        if (!kind.isInstance(type)) throw new IllegalStateException("a " + sample + " at type " + type);
    }

    private static PirType named(PirType.NamedTypeRef ref, TypeRegistry.Snapshot registry) {
        return ref.kind() == PirType.NamedKind.RECORD ? registry.records().get(ref.stableId()) : registry.sums().get(ref.stableId());
    }

    private static PlutusData record(RecordSample sample, PirType at, Layout layout) {
        var registry = layout.registry();
        String simple = sample.identity().substring(sample.identity().lastIndexOf('.') + 1);
        boolean fits = switch (at) {
            case PirType.RecordType record -> record.name().equals(simple);
            case PirType.SumType sum -> sum.constructors().stream().anyMatch(c -> c.name().equals(simple));
            default -> false;
        };
        if (!fits) throw new IllegalStateException("a " + sample.identity() + " at type " + at);

        var parents = registry.variants().get(sample.identity());
        int tag = 0;
        List<PirType.Field> fields;
        if (parents != null) {
            // Two sealed parents (#279, rejected by ADR-067 D6): the last registered one gives the tag, as in lowering.
            var constructor = parents.getLast().constructors().stream().filter(c -> c.name().equals(simple)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no constructor " + simple + " in " + parents.getLast()));
            tag = constructor.tag();
            fields = constructor.fields();
        } else if (registry.records().containsKey(sample.identity())) {
            fields = registry.records().get(sample.identity()).fields();
        } else {
            fields = ((PirType.RecordType) at).fields();     // Tuple2, Tuple3, AssetEntry: built-in, not registered
        }
        if (fields.size() != sample.fields().size()) {
            throw new IllegalStateException(sample.identity() + " has " + fields.size() + " fields, its sample "
                    + sample.fields().size());
        }
        var data = new ArrayList<PlutusData>();
        for (var field : fields) {
            int index = sample.names().indexOf(field.name());
            if (index < 0) throw new IllegalStateException("no component " + field.name() + " in " + sample.identity());
            data.add(encode(sample.fields().get(index), field.type(), layout));
        }
        // A record whose Data is not a constructor is its one field's Data: Value is its map.
        if (layout.notConstructorData().contains(sample.identity())) {
            if (data.size() != 1) throw new IllegalStateException(sample.identity() + " is not one field");
            return data.getFirst();
        }
        return new PlutusData.ConstrData(tag, data);
    }

    /** The Data's digest, as the frozen files hold it. */
    static String digest(PlutusData data) {
        return CorpusFiles.sha256(PlutusDataCborEncoder.encode(data)).substring(0, 32);
    }

    /** A type's layout key: its identity and the digest of its registered definition. */
    static String layout(String identity, PirType definition) {
        return identity + "\t" + CorpusFiles.sha256(definition.toString()).substring(0, 16);
    }

    /** Identities of the ledger's declarations: not probed from corpus cases, they have a table of their own. */
    static boolean ledger(String identity) {
        return identity.startsWith(LedgerLayouts.PACKAGE + ".");
    }
}
