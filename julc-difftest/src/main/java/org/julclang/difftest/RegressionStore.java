package org.julclang.difftest;

import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Saved cases with their expected outcome (ADR-063 D8). A case file is a header, a {@code ---}
 * line and the Java source:
 * <pre>
 * name: any stops at the first match (T7)
 * class: Probe
 * method: m
 * arg: 9f0500ff                  (one line per argument: CBOR of the Data, in hex)
 * expect: DIVERGE T7 Value/Failure  (AGREE, REJECT &lt;code&gt;, REJECT none, or DIVERGE &lt;catalogue id&gt;
 *                                  &lt;JVM kind&gt;/&lt;UPLC kind&gt;)
 * expect.PV11_SAFE: AGREE        (optional, one line per optimization level that differs)
 * note: free text, kept with the case
 * ---
 * import java.math.BigInteger;
 * public class Probe { ... }
 * </pre>
 * A {@code DIVERGE} expectation is the allowed-divergence rule for a hand-written case (D6). It
 * names the catalogue entry and the {@link Observation} kind each side must produce, so a
 * mismatch with another cause does not pass. The case must keep diverging until the owning step
 * resolves its entry; then the expectation changes to {@code AGREE} or {@code REJECT}. Cases are
 * never deleted.
 */
public final class RegressionStore {

    private RegressionStore() {
    }

    /** A saved case and what every configuration must produce for it. */
    public record SavedCase(Path file, DiffCase diffCase, Expectation expectation,
                            Map<OptimizationLevel, Expectation> overrides, String note) {

        public Expectation expectationFor(OptimizationLevel level) {
            return overrides.getOrDefault(level, expectation);
        }
    }

    /** The expected verdict of one configuration. */
    public record Expectation(Verdict.Kind kind, String detail, String jvmKind, String uplcKind) {

        /** The code of a rejection whose diagnostic has none ({@code REJECT none}). */
        public static final String NO_CODE = "none";

        static Expectation parse(String text) {
            String[] parts = text.trim().split("\\s+");
            return switch (parts[0]) {
                case "AGREE" -> new Expectation(Verdict.Kind.AGREE, null, null, null);
                case "REJECT" -> {
                    if (parts.length != 2) throw new HarnessException("Expected REJECT <code>: " + text);
                    yield new Expectation(Verdict.Kind.REJECTED, parts[1], null, null);
                }
                case "DIVERGE" -> {
                    String[] kinds = parts.length == 3 ? parts[2].split("/") : new String[0];
                    if (kinds.length != 2) throw new HarnessException("Expected DIVERGE <id> <JVM kind>/<UPLC kind>: " + text);
                    yield new Expectation(Verdict.Kind.MISMATCH, parts[1], kinds[0], kinds[1]);
                }
                default -> throw new HarnessException("Unknown expectation: " + text);
            };
        }

        /** Null when the result meets this expectation, else the reason it does not. */
        public String check(DiffRunner.Result result) {
            var verdict = result.verdict();
            // A known divergence whose UPLC side never finishes is inconclusive, not a mismatch; the
            // observation kinds below still pin it exactly.
            boolean divergesInconclusively = kind == Verdict.Kind.MISMATCH && verdict.kind() == Verdict.Kind.INCONCLUSIVE;
            if (verdict.kind() != kind && !divergesInconclusively) {
                return "expected " + this + ", got " + verdict.kind() + (verdict.reason() == null ? "" : " (" + verdict.reason() + ")");
            }
            if (kind == Verdict.Kind.REJECTED && result.uplc() instanceof Observation.Rejected r
                    && !detail.equals(r.code() == null ? NO_CODE : r.code())) {
                return "expected rejection " + detail + ", got " + r.code() + ": " + r.message();
            }
            if (kind == Verdict.Kind.MISMATCH) {
                String jvm = result.jvm().getClass().getSimpleName();
                String uplc = result.uplc().getClass().getSimpleName();
                if (!jvm.equals(jvmKind) || !uplc.equals(uplcKind)) {
                    return "expected " + this + ", got " + jvm + "/" + uplc + " (" + verdict.reason() + ")";
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return switch (kind) {
                case AGREE -> "AGREE";
                case REJECTED -> "REJECT " + detail;
                case MISMATCH -> "DIVERGE " + detail + " " + jvmKind + "/" + uplcKind;
                default -> kind.name();
            };
        }
    }

    /** Every {@code .diffcase} file under the directory, sorted by path. */
    public static List<SavedCase> load(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(f -> f.toString().endsWith(".diffcase")).sorted().map(RegressionStore::read).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static SavedCase read(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int separator = text.indexOf("\n---\n");
        if (separator < 0) throw new HarnessException(file + ": no --- line between header and source");
        String name = null, className = null, method = null, note = null;
        Expectation expectation = null;
        var overrides = new EnumMap<OptimizationLevel, Expectation>(OptimizationLevel.class);
        var args = new ArrayList<PlutusData>();
        for (String line : text.substring(0, separator).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) throw new HarnessException(file + ": header line without a key: " + line);
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "name" -> name = value;
                case "class" -> className = value;
                case "method" -> method = value;
                case "note" -> note = note == null ? value : note + " " + value;
                case "arg" -> args.add(PlutusDataCborDecoder.decode(HexFormat.of().parseHex(value)));
                case "expect" -> expectation = Expectation.parse(value);
                default -> {
                    if (!key.startsWith("expect.")) throw new HarnessException(file + ": unknown header " + key);
                    overrides.put(OptimizationLevel.valueOf(key.substring("expect.".length())), Expectation.parse(value));
                }
            }
        }
        if (name == null || className == null || method == null || expectation == null) {
            throw new HarnessException(file + ": name, class, method and expect are required");
        }
        var diffCase = new DiffCase(name, text.substring(separator + 5), className, method, args);
        return new SavedCase(file, diffCase, expectation, overrides, note);
    }

    /** Writes a case so that {@link #read} returns it unchanged; used to save shrunk failures. */
    public static void write(Path file, DiffCase diffCase, Expectation expectation, String note) {
        var text = new StringBuilder();
        text.append("name: ").append(diffCase.name()).append('\n');
        text.append("class: ").append(diffCase.className()).append('\n');
        text.append("method: ").append(diffCase.method()).append('\n');
        for (PlutusData arg : diffCase.args()) {
            text.append("arg: ").append(HexFormat.of().formatHex(PlutusDataCborEncoder.encode(arg))).append('\n');
        }
        text.append("expect: ").append(expectation).append('\n');
        if (note != null) text.append("note: ").append(note).append('\n');
        text.append("---\n").append(diffCase.source());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
