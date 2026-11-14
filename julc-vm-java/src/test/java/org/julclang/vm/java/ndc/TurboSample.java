package org.julclang.vm.java.ndc;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * One uplc-turbo script: {@code <epoch>/<name>-v<n>.flat}, a CBOR byte string holding the Flat program, as the script
 * appears in a transaction witness set.
 *
 * @param bench         the file name, which the scenario names the benchmark by
 * @param language      1, 2 or 3, from the file name's suffix
 * @param protocolMajor the major protocol version of the epoch the script ran in
 * @param file          the file
 */
record TurboSample(String bench, int language, int protocolMajor, Path file) {

    String plutusVersion() {
        return "V" + language;
    }

    String protocolVersion() {
        return protocolMajor + ".0";
    }

    /** @return the Flat program */
    byte[] flat() {
        try {
            return unwrap(Files.readAllBytes(file), file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the content of {@code cbor}, which must be exactly one CBOR byte string */
    static byte[] unwrap(byte[] cbor, String source) {
        try {
            List<DataItem> items = CborDecoder.decode(cbor);
            if (items.size() != 1 || !(items.getFirst() instanceof ByteString bytes)) {
                throw new IllegalArgumentException(source + " is not one CBOR byte string");
            }
            return bytes.getBytes();
        } catch (CborException e) {
            throw new IllegalArgumentException(source + " is not CBOR", e);
        }
    }

    /** @return every sample below {@code root} (one folder per epoch), sorted by path */
    static List<TurboSample> list(Path root) {
        try (Stream<Path> files = Files.walk(root, 2)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".flat"))
                    .filter(p -> p.getParent().getFileName().toString().matches("\\d+"))
                    .sorted(Comparator.naturalOrder())
                    .map(p -> of(Integer.parseInt(p.getParent().getFileName().toString()), p))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TurboSample of(int epoch, Path file) {
        String name = file.getFileName().toString();
        if (!name.matches(".*-v[123]\\.flat")) {
            throw new IllegalArgumentException("no Plutus version suffix (-v1, -v2 or -v3): " + name);
        }
        int language = name.charAt(name.length() - ".flat".length() - 1) - '0';
        return new TurboSample(name, language, protocolMajor(epoch), file);
    }

    /** The scenario's epochs: from 644 protocol version 11, from 537 10, from 507 9, before that 8. */
    static int protocolMajor(int epoch) {
        if (epoch >= 644) {
            return 11;
        }
        if (epoch >= 537) {
            return 10;
        }
        return epoch >= 507 ? 9 : 8;
    }
}
