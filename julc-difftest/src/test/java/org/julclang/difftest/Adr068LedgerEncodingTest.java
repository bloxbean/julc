package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.LedgerTypeProvider;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.PlutusDataConvertible;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 I11: the Data of every ledger type. The expected value is the ledger's own hand-written {@code toPlutusData}
 * on a real Java value, frozen in {@code adr068/ledger-encodings-b1.tsv}; the javac frontend's registry, read from the
 * stubs, must give the same Data through {@link Adr068Encodings#encode}, which takes tags, order and field types only
 * from the registry and the frontend's description, and checks each field against the type the registry gives. The
 * JavaParser frontend's registry through the same encoder is the encoder's own control.
 */
class Adr068LedgerEncodingTest {

    private static final Path FROZEN = Path.of("src/test/resources/adr068/ledger-encodings-b1.tsv");
    private static final String TRIVIAL = "class P {}";

    /** A Java value and the same value as a sample. */
    private record Built(Object value, Adr068Encodings.Sample sample) {
    }

    private int leaf = 1;

    private Built build(Type type) throws ReflectiveOperationException {
        int n = leaf++;
        Class<?> raw = type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) type;
        BigInteger integer = BigInteger.valueOf(n);
        if (raw == BigInteger.class) return new Built(integer, new Adr068Encodings.IntegerSample(integer, ""));
        if (raw == long.class) return new Built((long) n, new Adr068Encodings.IntegerSample(integer, ""));
        if (raw == int.class) return new Built(n, new Adr068Encodings.IntegerSample(integer, ""));
        if (raw == boolean.class) return new Built(n % 2 == 0, new Adr068Encodings.BoolSample(n % 2 == 0, ""));
        if (raw == byte[].class) return new Built(bytes(n), new Adr068Encodings.BytesSample(bytes(n), ""));
        if (raw == PlutusData.class) return new Built(new PlutusData.IntData(integer), new Adr068Encodings.DataSample(integer, ""));
        if (raw == Optional.class) {
            var value = build(argument(type, 0));
            return new Built(Optional.of(value.value()), new Adr068Encodings.OptionalSample(value.sample(), ""));
        }
        if (raw == JulcList.class) {
            var item = build(argument(type, 0));
            return new Built(JulcList.of(item.value()), new Adr068Encodings.ListSample(List.of(item.sample()), ""));
        }
        if (raw == JulcMap.class) {
            var key = build(argument(type, 0));
            var value = build(argument(type, 1));
            return new Built(JulcMap.of(key.value(), value.value()), new Adr068Encodings.MapSample(key.sample(), value.sample(), ""));
        }
        if (TypeRegistry.ledgerHashFqcns().contains(raw.getName())) {
            return new Built(raw.getConstructor(byte[].class).newInstance((Object) bytes(n)), new Adr068Encodings.BytesSample(bytes(n), ""));
        }
        if (raw.isRecord()) return record(raw);
        if (raw.isSealed()) return record(raw.getPermittedSubclasses()[0]);
        throw new IllegalStateException("no sample of " + type);
    }

    private static Type argument(Type type, int index) {
        return ((ParameterizedType) type).getActualTypeArguments()[index];
    }

    private static byte[] bytes(int n) {
        return new byte[] {(byte) (n & 0x7f), (byte) (n >> 7 & 0x7f)};
    }

    private Built record(Class<?> record) throws ReflectiveOperationException {
        var components = record.getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        var names = new ArrayList<String>();
        var samples = new ArrayList<Adr068Encodings.Sample>();
        for (int i = 0; i < components.length; i++) {
            var built = build(components[i].getGenericType());
            types[i] = components[i].getType();
            values[i] = built.value();
            names.add(components[i].getName());
            samples.add(built.sample());
        }
        var constructor = record.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return new Built(constructor.newInstance(values),
                new Adr068Encodings.RecordSample(record.getCanonicalName(), names, samples, ""));
    }

    private static Class<?> ledgerClass(String identity) throws ClassNotFoundException {
        String name = identity;
        while (true) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                int dot = name.lastIndexOf('.');
                if (dot < 0) throw e;
                name = name.substring(0, dot) + "$" + name.substring(dot + 1);
            }
        }
    }

    private static String hex(PlutusData data) {
        return HexFormat.of().formatHex(PlutusDataCborEncoder.encode(data));
    }

    private static TreeMap<String, String> frozen() throws IOException {
        var rows = new TreeMap<String, String>();
        for (String line : Files.readAllLines(FROZEN)) {
            if (line.startsWith("#")) continue;
            String[] cells = line.split("\t");
            rows.put(cells[0], cells[1]);
        }
        return rows;
    }

    /** A frontend's reading of the ledger: its registry, and the records its description says are not constructors. */
    private static Adr068Encodings.Layout layout(TypeRegistry.Snapshot registry, LedgerTypeProvider described) {
        var notConstructorData = new TreeSet<String>();
        described.types().forEach((identity, type) -> {
            if (type.operations().isEmpty()) notConstructorData.add(identity);
        });
        return new Adr068Encodings.Layout(registry, notConstructorData);
    }

    private static Adr068Encodings.Layout javacLayout() {
        return layout(JavacProgram.analyze(List.of(JavacFrontend.Unit.of(TRIVIAL)), List.of()).registry().snapshot(),
                new LedgerTypeProvider(new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)));
    }

    @Test
    void everyLedgerTypesDataEqualsTheLedgersOwnEncoder() throws Exception {
        var javac = javacLayout();
        var reference = layout(Adr068Types.reference(List.of(TRIVIAL)).registry(), new LedgerTypeProvider());
        var identities = new TreeMap<>(javac.registry().records()).keySet();
        assertFalse(identities.isEmpty());
        var actual = new TreeMap<String, String>();
        for (String identity : identities) {
            leaf = 1;
            var built = record(ledgerClass(identity));
            var expected = ((PlutusDataConvertible) built.value()).toPlutusData();
            assertEquals(hex(expected), hex(Adr068Encodings.encode(built.sample(), javac.registry().records().get(identity), javac)),
                    "the javac registry's Data of " + identity);
            assertEquals(hex(expected), hex(Adr068Encodings.encode(built.sample(), reference.registry().records().get(identity), reference)),
                    "the JavaParser registry's Data of " + identity);
            actual.put(identity, hex(expected));
        }
        var registry = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(TRIVIAL)), List.of()).registry();
        for (String identity : new TreeSet<>(TypeRegistry.ledgerHashFqcns())) {
            leaf = 1;
            var built = build(ledgerClass(identity));
            var expected = ((PlutusDataConvertible) built.value()).toPlutusData();
            assertEquals(hex(expected), hex(Adr068Encodings.encode(built.sample(),
                    registry.lookupNominal(identity).orElseThrow(), javac)), identity);
            actual.put(identity, hex(expected));
        }
        String freeze = System.getenv("ADR068_FREEZE_LEDGER");
        if (freeze != null) {
            var lines = new ArrayList<String>();
            lines.add("# ADR-068 I11: each ledger type's sample as the ledger's toPlutusData encodes it (CBOR hex). Generated; do not edit.");
            actual.forEach((identity, data) -> lines.add(identity + "\t" + data));
            Files.write(Path.of(freeze), lines);
        }
        assertEquals(frozen(), actual);
    }

    /**
     * {@code Value}: the registry's {@code inner} type gives the map, and the frontend's description says the record is
     * not a constructor (I11), which is why the map is the Data. Each half is what the test above reads, so here they
     * are stated, with the Data's shape written out. A {@code Constr} around the map, or another {@code inner}, fails.
     */
    @Test
    void aValueIsItsInnerMap() {
        var javac = javacLayout();
        assertEquals(Set.of(Value.class.getName()), javac.notConstructorData());
        var value = javac.registry().records().get(Value.class.getName());
        var hash = new PirType.ByteStringType();
        assertEquals(new PirType.MapType(hash, new PirType.MapType(hash, new PirType.IntegerType())),
                value.fields().getFirst().type());
        var none = new Adr068Encodings.BytesSample(new byte[0], "");
        var amount = new Adr068Encodings.IntegerSample(BigInteger.valueOf(5), "");
        var sample = new Adr068Encodings.RecordSample(Value.class.getName(), List.of("inner"), List.of(
                new Adr068Encodings.MapSample(none, new Adr068Encodings.MapSample(none, amount, ""), "")), "");
        var data = Adr068Encodings.encode(sample, value, javac);
        assertInstanceOf(PlutusData.MapData.class, data);
        assertEquals(hex(Value.lovelace(BigInteger.valueOf(5)).toPlutusData()), hex(data));
    }

    /** The encoder takes nothing on trust: a sample that does not fit the type the registry gives is a failure. */
    @Test
    void aFieldOfAnotherTypeIsAFailureNotData() {
        var javac = javacLayout();
        var out = javac.registry().records().get("org.julclang.ledger.TxOut");
        var wrong = new TreeMap<>(javac.registry().records());
        var fields = new ArrayList<>(out.fields());
        fields.set(0, new PirType.Field("address", new PirType.IntegerType()));
        wrong.put("org.julclang.ledger.TxOut", new PirType.RecordType("TxOut", fields));
        leaf = 1;
        var retyped = new Adr068Encodings.Layout(new TypeRegistry.Snapshot(wrong, javac.registry().sums(),
                javac.registry().variants(), javac.registry().newTypes(), javac.registry().parameterized()), javac.notConstructorData());
        Built built;
        try {
            built = record(TxOut.class);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        var e = assertThrows(IllegalStateException.class,
                () -> Adr068Encodings.encode(built.sample(), wrong.get("org.julclang.ledger.TxOut"), retyped));
        assertTrue(e.getMessage().contains("org.julclang.ledger.Address at type IntegerType"), e.getMessage());
    }
}
