package org.julclang.tools.params;

import org.julclang.core.Program;
import org.julclang.core.flat.FlatReader;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.vm.DecodeLimits;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * A script whose bytes are canonical at every layer (ADR-077 D6a, "Canonical script bytes"):
 * <ul>
 *   <li>each CBOR byte-string wrapper is definite, has a minimal length header, and has no bytes after it;</li>
 *   <li>decoding the program and encoding it again gives the input's FLAT bytes exactly.</li>
 * </ul>
 * So the bytes this class gives back equal the input's, and nothing is silently normalised.
 *
 * @param program    the decoded program
 * @param flat       its FLAT bytes, equal to the input's and to the re-encoding
 * @param singleCbor the FLAT bytes wrapped once, as the script hash and an Aiken blueprint take them
 * @param wrappers   how many CBOR wrappers the input had: 0 (raw FLAT), 1 or 2
 */
record CanonicalScript(Program program, byte[] flat, byte[] singleCbor, int wrappers) {

    /** Reads raw FLAT, or FLAT wrapped once or twice as a CBOR byte string. */
    static CanonicalScript read(byte[] bytes) {
        if (bytes.length == 0) throw ApplyParamsException.invalidRequest("The script is empty");
        byte[] current = bytes;
        int wrappers = 0;
        // FLAT for a program of major version 1 starts with 0x01; a byte of major type 2 (0x40 to 0x5f) starts a
        // CBOR byte string, as in ScriptDecoder.
        while (wrappers < 2 && isByteStringHead(current[0])) {
            current = unwrap(current, wrappers == 0 ? "outer" : "inner");
            wrappers++;
            if (current.length == 0) {
                throw ApplyParamsException.invalidRequest("The script's CBOR wrapper holds no bytes");
            }
        }
        byte[] flat = current;
        Program program = decodeFlat(flat);
        byte[] again = UplcFlatEncoder.encodeProgram(program);
        if (!Arrays.equals(flat, again)) {
            throw ApplyParamsException.invalidRequest("The script's FLAT encoding is not canonical: decoding it and"
                    + " encoding it again differs from byte " + firstDifference(flat, again) + " (of " + flat.length
                    + "), for example a data constant not in Plutus's canonical Data encoding, or padding that is not"
                    + " minimal. A non-canonical script is refused rather than silently normalised.");
        }
        return new CanonicalScript(program, flat, wrap(flat), wrappers);
    }

    /** The program's FLAT bytes wrapped once. */
    static CanonicalScript ofProgram(Program program) {
        byte[] flat = UplcFlatEncoder.encodeProgram(program);
        return new CanonicalScript(program, flat, wrap(flat), 1);
    }

    /** The once-wrapped script wrapped once more, as JuLC blueprints and text envelopes hold it. */
    byte[] doubleCbor() {
        return wrap(singleCbor);
    }

    private static boolean isByteStringHead(byte head) {
        return (head & 0xE0) == 0x40;
    }

    /** The payload of a definite, minimally encoded CBOR byte string that fills {@code bytes} exactly. */
    static byte[] unwrap(byte[] bytes, String layer) {
        int info = bytes[0] & 0x1F;
        long length;
        int header;
        if (info < 24) {
            length = info;
            header = 1;
        } else if (info <= 27) {
            int size = 1 << (info - 24);
            if (bytes.length < 1 + size) {
                throw ApplyParamsException.invalidRequest("The " + layer + " CBOR wrapper is truncated");
            }
            length = 0;
            for (int i = 1; i <= size; i++) length = (length << 8) | (bytes[i] & 0xFF);
            header = 1 + size;
            long minimum = switch (info) {
                case 24 -> 24;
                case 25 -> 0x100;
                case 26 -> 0x10000;
                default -> 0x100000000L;
            };
            if (Long.compareUnsigned(length, minimum) < 0) {
                throw ApplyParamsException.invalidRequest("The " + layer + " CBOR wrapper has a non-minimal length"
                        + " header (" + length + " bytes written with " + size + " length bytes)");
            }
        } else if (info == 31) {
            throw ApplyParamsException.invalidRequest("The " + layer + " CBOR wrapper is an indefinite-length byte"
                    + " string; only a definite one is accepted");
        } else {
            throw ApplyParamsException.invalidRequest("The " + layer + " CBOR wrapper has a malformed length header");
        }
        long remaining = bytes.length - header;
        if (Long.compareUnsigned(length, remaining) > 0) {
            throw ApplyParamsException.invalidRequest("The " + layer + " CBOR wrapper is truncated: it declares "
                    + Long.toUnsignedString(length) + " bytes and holds " + remaining);
        }
        if (length < remaining) {
            throw ApplyParamsException.invalidRequest("The " + layer + " CBOR wrapper is followed by "
                    + (remaining - length) + " trailing bytes");
        }
        return Arrays.copyOfRange(bytes, header, bytes.length);
    }

    /** A definite CBOR byte string with a minimal length header. */
    static byte[] wrap(byte[] payload) {
        var out = new ByteArrayOutputStream(payload.length + 9);
        long length = payload.length;
        if (length < 24) {
            out.write(0x40 | (int) length);
        } else if (length < 0x100) {
            out.write(0x58);
            out.write((int) length);
        } else if (length < 0x10000) {
            out.write(0x59);
            out.write((int) (length >> 8));
            out.write((int) length & 0xFF);
        } else {
            out.write(0x5A);
            for (int shift = 24; shift >= 0; shift -= 8) out.write((int) (length >> shift) & 0xFF);
        }
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static Program decodeFlat(byte[] flat) {
        FlatReader reader = new FlatReader(flat);
        Program program;
        try {
            // Data constants are read by a stack-safe reader that accepts only the canonical encoding.
            program = new UplcFlatDecoder(reader, DecodeLimits.PV11.toFlatDecodeLimits(),
                    new CanonicalDataCbor.ScriptConstants()).readProgram();
        } catch (ApplyParamsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApplyParamsException.invalidRequest("The script is not a valid UPLC program: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        if (reader.hasMore()) {
            throw ApplyParamsException.invalidRequest("The script has trailing bytes after the program");
        }
        return program;
    }

    private static int firstDifference(byte[] a, byte[] b) {
        int mismatch = Arrays.mismatch(a, b);
        return mismatch < 0 ? Math.min(a.length, b.length) : mismatch;
    }
}
