package org.julclang.difftest;

import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.PlutusDataConvertible;
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
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-068 I11: the Data of every ledger type. The expected value is the ledger's own hand-written {@code toPlutusData}
 * on a real Java value, frozen in {@code adr068/ledger-encodings-b1.tsv}; the javac frontend's registry, read from the
 * stubs, must give the same Data through {@link Adr068Encodings#encode}, which takes tags, order and field types only
 * from the registry. The JavaParser frontend's registry through the same encoder is the encoder's own control.
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
        if (raw == Value.class) return new Built(Value.lovelace(integer), new Adr068Encodings.ValueSample(integer, ""));
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

    @Test
    void everyLedgerTypesDataEqualsTheLedgersOwnEncoder() throws Exception {
        var javac = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(TRIVIAL)), List.of()).registry().snapshot();
        var reference = Adr068Types.reference(List.of(TRIVIAL)).registry();
        var identities = new TreeMap<>(javac.records()).keySet();
        assertFalse(identities.isEmpty());
        var actual = new TreeMap<String, String>();
        for (String identity : identities) {
            leaf = 1;
            Class<?> type = ledgerClass(identity);
            var built = record(type);
            var expected = ((PlutusDataConvertible) built.value()).toPlutusData();
            // Value's map layout is LedgerLayouts', not its record's; aValueIsAMap covers it.
            if (type != Value.class) {
                assertEquals(hex(expected), hex(Adr068Encodings.encode(built.sample(), javac.records().get(identity), javac)),
                        "the javac registry's Data of " + identity);
                assertEquals(hex(expected), hex(Adr068Encodings.encode(built.sample(), reference.records().get(identity), reference)),
                        "the JavaParser registry's Data of " + identity);
            }
            actual.put(identity, hex(expected));
        }
        for (String identity : new TreeSet<>(TypeRegistry.ledgerHashFqcns())) {
            leaf = 1;
            var built = build(ledgerClass(identity));
            var expected = ((PlutusDataConvertible) built.value()).toPlutusData();
            var type = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(TRIVIAL)), List.of()).registry()
                    .lookupNominal(identity).orElseThrow();
            assertEquals(hex(expected), hex(Adr068Encodings.encode(built.sample(), type, javac)), identity);
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

    /** {@code Value} is a map, by {@code LedgerLayouts}, whatever its record declares (I11). */
    @Test
    void aValueIsAMap() {
        var javac = JavacProgram.analyze(List.of(JavacFrontend.Unit.of(TRIVIAL)), List.of()).registry().snapshot();
        var amount = BigInteger.valueOf(5);
        assertEquals(hex(Value.lovelace(amount).toPlutusData()), hex(Adr068Encodings.encode(
                new Adr068Encodings.ValueSample(amount, ""), javac.records().get("org.julclang.ledger.Value"), javac)));
    }
}
