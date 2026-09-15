package org.julclang.playground.uplc;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.FlatReader;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.text.UplcNames;
import org.julclang.core.text.UplcParser;
import org.julclang.playground.model.MockTransaction.DataInput;
import org.julclang.playground.model.UplcModels.ScriptInput;
import org.julclang.vm.DecodeLimits;
import org.julclang.vm.PlutusLanguage;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Turns a pasted compiled script into a {@link Program} with the facts shown next to it: how it was wrapped, its
 * Plutus language and its script hash.
 * <p>
 * Accepted input: CBOR hex (double-wrapped as in text envelopes and JuLC blueprints, single-wrapped as in Aiken
 * blueprints, or raw FLAT), a CIP-57 blueprint, a cardano-cli text envelope, or UPLC text. The script hash is
 * {@code blake2b-224(language tag || single-CBOR script bytes)} over the pasted bytes; with parameters applied it
 * is computed over the re-encoded script.
 */
public final class ScriptDecoder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern HEX_TEXT = Pattern.compile("[0-9a-fA-F]*");

    /**
     * A decoded script.
     *
     * @param program        the program with parameters applied and unique binder names
     * @param language       Plutus language used for hashing and evaluation
     * @param languageSource where the language came from
     * @param scriptHash     script hash of {@code program}
     * @param compiledCode   double-CBOR encoding of {@code program}
     * @param flatBytes      FLAT size of {@code program}
     */
    public record DecodedScript(Program program, PlutusLanguage language, String languageSource,
                                String inputFormat, String wrapping, byte[] scriptHash, byte[] compiledCode,
                                int flatBytes, int termCount, List<String> builtins, int paramsApplied,
                                String validator, List<String> validators, List<String> warnings) {}

    private ScriptDecoder() {}

    public static DecodedScript decode(ScriptInput input) {
        if (input == null || input.script() == null || input.script().isBlank()) {
            throw new IllegalArgumentException("Paste a compiled script (CBOR hex, blueprint JSON, text envelope or UPLC)");
        }
        String text = input.script().strip();
        var warnings = new ArrayList<String>();
        String inputFormat;
        String languageHint = null;
        String hintSource = null;
        String expectedHash = null;
        String validator = null;
        List<String> validators = List.of();

        if (text.startsWith("{")) {
            JsonNode root = readJson(text);
            if (root.has("validators")) {
                inputFormat = "blueprint";
                var nodes = root.get("validators");
                validators = new ArrayList<>();
                for (JsonNode v : nodes) validators.add(v.path("title").asText(""));
                if (validators.isEmpty()) throw new IllegalArgumentException("The blueprint has no validators");
                int index = selectValidator(validators, input.validator());
                JsonNode chosen = nodes.get(index);
                validator = validators.get(index);
                if (!chosen.hasNonNull("compiledCode")) {
                    throw new IllegalArgumentException("Validator '" + validator + "' has no compiledCode");
                }
                text = chosen.get("compiledCode").asText();
                expectedHash = chosen.path("hash").asText(null);
                languageHint = languageOf(root.path("preamble").path("plutusVersion").asText(""));
                hintSource = "blueprint";
            } else if (root.has("cborHex")) {
                inputFormat = "envelope";
                text = root.get("cborHex").asText();
                languageHint = languageOf(root.path("type").asText(""));
                hintSource = "envelope";
            } else {
                throw new IllegalArgumentException("JSON input must be a CIP-57 blueprint or a text envelope with cborHex");
            }
        } else if (text.startsWith("(")) {
            inputFormat = "uplc";
        } else {
            inputFormat = "hex";
        }

        Program original;
        byte[] singleCbor;
        String wrapping;
        if (inputFormat.equals("uplc")) {
            original = UplcParser.parseProgram(text);
            singleCbor = cborWrap(UplcFlatEncoder.encodeProgram(original));
            wrapping = "text";
        } else {
            byte[] bytes = parseHex(text);
            byte[] flat;
            if (isCborByteString(bytes)) {
                byte[] once = cborUnwrap(bytes);
                if (isCborByteString(once)) {
                    wrapping = "double-cbor";
                    singleCbor = once;
                    flat = cborUnwrap(once);
                } else {
                    wrapping = "single-cbor";
                    singleCbor = bytes;
                    flat = once;
                }
            } else {
                wrapping = "flat";
                flat = bytes;
                singleCbor = cborWrap(bytes);
            }
            original = decodeFlat(flat, warnings);
        }

        var stats = stats(original.term());
        String language = input.language() == null || input.language().isBlank() || input.language().equalsIgnoreCase("auto")
                ? null : languageOf(input.language());
        String languageSource;
        if (input.language() != null && !input.language().isBlank() && !input.language().equalsIgnoreCase("auto")) {
            if (language == null) throw new IllegalArgumentException("Unknown language: " + input.language());
            languageSource = "user";
        } else if (expectedHash != null && matchingLanguage(singleCbor, expectedHash) != null) {
            language = matchingLanguage(singleCbor, expectedHash);
            languageSource = "hash";
        } else if (languageHint != null) {
            language = languageHint;
            languageSource = hintSource;
        } else if (original.major() == 1 && original.minor() >= 1) {
            language = "V3";
            languageSource = "version";
        } else if (stats.maxBuiltinCode() >= 54) {
            language = "V3";
            languageSource = "builtins";
        } else if (stats.maxBuiltinCode() >= 51) {
            language = "V2";
            languageSource = "builtins";
        } else {
            language = "V2";
            languageSource = "default";
            warnings.add("The Plutus language cannot be told from the script; assuming V2. Choose the language if the"
                    + " script hash does not match.");
        }
        PlutusLanguage plutusLanguage = PlutusLanguage.valueOf("PLUTUS_" + language);

        Program applied = original;
        List<DataInput> params = input.params() == null ? List.of() : input.params();
        if (!params.isEmpty()) {
            var data = new PlutusData[params.size()];
            for (int i = 0; i < params.size(); i++) {
                try {
                    data[i] = DataInputs.parse(params.get(i));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Parameter " + (i + 1) + ": " + e.getMessage(), e);
                }
                if (data[i] == null) throw new IllegalArgumentException("Parameter " + (i + 1) + " is empty");
            }
            applied = original.applyParams(data);
            singleCbor = cborWrap(UplcFlatEncoder.encodeProgram(applied));
        }

        byte[] hash = scriptHash(plutusLanguage, singleCbor);
        if (expectedHash != null && params.isEmpty() && !expectedHash.equalsIgnoreCase(HEX.formatHex(hash))) {
            warnings.add("The blueprint hash " + expectedHash + " does not match the computed hash.");
        }
        int flatBytes = cborUnwrap(singleCbor).length;
        return new DecodedScript(UplcNames.uniquify(applied), plutusLanguage, languageSource, inputFormat, wrapping,
                hash, cborWrap(singleCbor), flatBytes, stats.terms() + (applied == original ? 0 : 2 * params.size()),
                stats.builtins(), params.size(), validator, validators, warnings);
    }

    /** {@code blake2b-224(tag || script)} with tag 1, 2 or 3 for Plutus V1, V2 or V3. */
    public static byte[] scriptHash(PlutusLanguage language, byte[] singleCborScript) {
        byte tag = switch (language) {
            case PLUTUS_V1 -> 1;
            case PLUTUS_V2 -> 2;
            case PLUTUS_V3 -> 3;
        };
        var digest = new Blake2bDigest(224);
        digest.update(tag);
        digest.update(singleCborScript, 0, singleCborScript.length);
        byte[] out = new byte[28];
        digest.doFinal(out, 0);
        return out;
    }

    /** {@code blake2b-256} of the bytes, as used for datum hashes. */
    public static byte[] blake2b256(byte[] bytes) {
        var digest = new Blake2bDigest(256);
        digest.update(bytes, 0, bytes.length);
        byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }

    // ---- helpers ----

    private static Program decodeFlat(byte[] flat, List<String> warnings) {
        try {
            var reader = new FlatReader(flat);
            var program = new UplcFlatDecoder(reader, DecodeLimits.PV11.toFlatDecodeLimits()).readProgram();
            if (reader.hasMore()) {
                warnings.add("The script has trailing bytes after the program.");
            }
            return program;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a valid UPLC program: " + e.getMessage(), e);
        }
    }

    private static String matchingLanguage(byte[] singleCbor, String expectedHash) {
        for (PlutusLanguage language : PlutusLanguage.values()) {
            if (HEX.formatHex(scriptHash(language, singleCbor)).equalsIgnoreCase(expectedHash)) {
                return language.name().substring("PLUTUS_".length());
            }
        }
        return null;
    }

    private static String languageOf(String text) {
        String t = text.toUpperCase(Locale.ROOT);
        if (t.endsWith("V1")) return "V1";
        if (t.endsWith("V2")) return "V2";
        if (t.endsWith("V3")) return "V3";
        return null;
    }

    private static int selectValidator(List<String> titles, String requested) {
        if (requested == null || requested.isBlank()) return 0;
        int byTitle = titles.indexOf(requested);
        if (byTitle >= 0) return byTitle;
        try {
            int index = Integer.parseInt(requested.strip());
            if (index >= 0 && index < titles.size()) return index;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("No validator '" + requested + "' in the blueprint");
    }

    private static JsonNode readJson(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    private static byte[] parseHex(String text) {
        String clean = text.replaceAll("\\s+", "");
        if (clean.startsWith("0x") || clean.startsWith("0X")) clean = clean.substring(2);
        if (clean.isEmpty() || clean.length() % 2 != 0 || !HEX_TEXT.matcher(clean).matches()) {
            throw new IllegalArgumentException("Expected hex-encoded CBOR, a blueprint or envelope JSON, or UPLC text");
        }
        return HEX.parseHex(clean);
    }

    private static boolean isCborByteString(byte[] bytes) {
        return bytes.length > 0 && (bytes[0] & 0xE0) == 0x40;
    }

    private static byte[] cborUnwrap(byte[] bytes) {
        try {
            List<DataItem> items = CborDecoder.decode(bytes);
            if (!items.isEmpty() && items.getFirst() instanceof ByteString bs) {
                return bs.getBytes();
            }
        } catch (CborException e) {
            throw new IllegalArgumentException("Invalid CBOR: " + e.getMessage(), e);
        }
        throw new IllegalArgumentException("Expected a CBOR byte string");
    }

    static byte[] cborWrap(byte[] bytes) {
        try {
            var out = new ByteArrayOutputStream(bytes.length + 5);
            new CborEncoder(out).encode(new ByteString(bytes));
            return out.toByteArray();
        } catch (CborException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Stats(int terms, List<String> builtins, int maxBuiltinCode) {}

    private static Stats stats(Term root) {
        int terms = 0;
        int maxCode = -1;
        var builtins = new TreeSet<String>();
        var stack = new ArrayDeque<Term>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Term term = stack.pop();
            terms++;
            switch (term) {
                case Term.Lam l -> stack.push(l.body());
                case Term.Apply a -> {
                    stack.push(a.argument());
                    stack.push(a.function());
                }
                case Term.Force f -> stack.push(f.term());
                case Term.Delay d -> stack.push(d.term());
                case Term.Constr c -> c.fields().forEach(stack::push);
                case Term.Case cs -> {
                    stack.push(cs.scrutinee());
                    cs.branches().forEach(stack::push);
                }
                case Term.Builtin b -> {
                    String name = b.fun().name();
                    builtins.add(Character.toLowerCase(name.charAt(0)) + name.substring(1));
                    maxCode = Math.max(maxCode, b.fun().flatCode());
                }
                default -> {
                }
            }
        }
        return new Stats(terms, List.copyOf(builtins), maxCode);
    }

    static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    static byte[] unhex(String text, String what) {
        if (text == null || text.length() % 2 != 0 || !HEX_TEXT.matcher(text).matches()) {
            throw new IllegalArgumentException(what + " must be hex: " + text);
        }
        return HEX.parseHex(text);
    }

    static boolean sameBytes(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
