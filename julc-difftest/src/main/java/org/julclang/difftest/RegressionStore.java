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
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Saved cases with their expected outcome (ADR-063 D6, D8). A case file is a header, a {@code ---}
 * line and the Java source:
 * <pre>
 * format: 2
 * name: ContextsLib.trace is a no-op off-chain (T12)
 * class: Probe
 * method: m
 * tag: Probe$A Probe$S 0           (format 3 only: one line per record and sealed parent, sorted by
 *                                   record, then parent; ADR-066 D3)
 * profile: G1 r1 -GUARDS           (format 3 only, optional: the generation profile; ADR-066 D6)
 * arg: 01                          (one line per argument: CBOR of the Data, in hex)
 * expect: DIVERGE T12 Value/Value  (AGREE, REJECT &lt;code&gt;, REJECT none, or DIVERGE &lt;catalogue id&gt;
 *                                  &lt;JVM kind&gt;/&lt;UPLC kind&gt;)
 * jvm: Value 01 []                 (DIVERGE only: the observation each side must produce)
 * uplc: Value 01 ["checked"]
 * expect.PV11_SAFE: AGREE          (optional, one line per optimization level that differs;
 * uplc.PV11_SAFE: ...               likewise for the UPLC observation)
 * note: free text, kept with the case
 * found.seed: 1234                 (optional provenance: found.by, found.seed, found.generator,
 *                                   found.plan, found.compiler)
 * recorded.jvm: Value 01 []        (optional: what each side observed when the case was saved,
 * recorded.Java/NONE: ...           with recorded.&lt;config&gt;.cost; never checked)
 * ---
 * import java.math.BigInteger;
 * public class Probe { ... }
 * </pre>
 * A {@code DIVERGE} expectation is the allowed-divergence rule for a saved case (D6). It names the
 * catalogue entry and pins both observations ({@link ObservationText}): the value and the traces of a
 * {@code Value}, the traces of a {@code Failure} (failure details are not compared, open question 3) and
 * the reason of an {@code Inconclusive}. So the case passes only while that exact divergence holds; a
 * different value, an extra or reordered trace, or another reason fails it. The case must keep
 * diverging until the owning step resolves its entry; then the expectation changes to {@code AGREE} or
 * {@code REJECT}. Cases are never deleted.
 * <p>
 * The {@code recorded.} lines are history: the observations of the run that found the case, which
 * replaying against a later compiler can contradict. The {@code expect} lines are what that replay
 * must produce.
 * <p>
 * Format 3 (ADR-066 D5) adds the {@code tag:} and {@code profile:} lines; format 2 files are read as before and
 * have neither. A case without tags and without a profile is written as format 2, so G0's records do not change.
 */
public final class RegressionStore {

    /** The newest header format this class reads and writes. */
    public static final int FORMAT = 3;
    /** The format of a case without tags and without a profile, which this class also reads and writes. */
    public static final int FORMAT_2 = 2;

    private RegressionStore() {
    }

    /** A saved case and what every configuration must produce for it. */
    public record SavedCase(Path file, DiffCase diffCase, Expectation expectation,
                            Map<OptimizationLevel, Expectation> overrides, String note,
                            Map<String, String> provenance, Map<String, String> recorded) {

        public Expectation expectationFor(OptimizationLevel level) {
            return overrides.getOrDefault(level, expectation);
        }
    }

    /**
     * The expected verdict of one configuration. For {@code DIVERGE}, {@code jvm} and {@code uplc} are the
     * pinned observations; they are null otherwise.
     */
    public record Expectation(Verdict.Kind kind, String detail, String jvmKind, String uplcKind,
                              Observation jvm, Observation uplc) {

        /** The code of a rejection whose diagnostic has none ({@code REJECT none}). */
        public static final String NO_CODE = "none";

        public static final Expectation AGREE = new Expectation(Verdict.Kind.AGREE, null, null, null, null, null);

        static Expectation parse(String text) {
            String[] parts = text.trim().split("\\s+");
            return switch (parts[0]) {
                case "AGREE" -> AGREE;
                case "REJECT" -> {
                    if (parts.length != 2) throw new HarnessException("Expected REJECT <code>: " + text);
                    yield new Expectation(Verdict.Kind.REJECTED, parts[1], null, null, null, null);
                }
                case "DIVERGE" -> {
                    String[] kinds = parts.length == 3 ? parts[2].split("/") : new String[0];
                    if (kinds.length != 2) throw new HarnessException("Expected DIVERGE <id> <JVM kind>/<UPLC kind>: " + text);
                    yield new Expectation(Verdict.Kind.MISMATCH, parts[1], kinds[0], kinds[1], null, null);
                }
                default -> throw new HarnessException("Unknown expectation: " + text);
            };
        }

        /** This divergence with its two pinned observations, which must have the kinds it names. */
        public Expectation pinned(Observation jvmPin, Observation uplcPin) {
            if (kind != Verdict.Kind.MISMATCH) throw new HarnessException("only DIVERGE pins observations: " + this);
            if (jvmPin == null || uplcPin == null) throw new HarnessException(this + " needs jvm: and uplc: observations");
            checkPin("jvm", jvmPin, jvmKind);
            checkPin("uplc", uplcPin, uplcKind);
            return new Expectation(kind, detail, jvmKind, uplcKind, jvmPin, uplcPin);
        }

        private static void checkPin(String side, Observation pin, String expectedKind) {
            if (!pin.getClass().getSimpleName().equals(expectedKind)) {
                throw new HarnessException(side + ": " + ObservationText.format(pin) + " is not a " + expectedKind);
            }
            switch (pin) {
                case Observation.Failure f when f.detail() != null -> throw new HarnessException(
                        side + ": failure details are not compared (ADR-063 open question 3); pin only the traces");
                case Observation.Rejected r -> throw new HarnessException(side + ": a rejection is REJECT, not DIVERGE");
                case Observation.Crash c -> throw new HarnessException(side + ": a crash is never an allowed divergence");
                default -> { }
            }
        }

        /** Null when the result meets this expectation, else the reason it does not. */
        public String check(DiffRunner.Result result) {
            var verdict = result.verdict();
            // A known divergence whose UPLC side never finishes is inconclusive, not a mismatch; the
            // pinned observations below still pin it exactly.
            boolean divergesInconclusively = kind == Verdict.Kind.MISMATCH && verdict.kind() == Verdict.Kind.INCONCLUSIVE;
            if (verdict.kind() != kind && !divergesInconclusively) {
                return "expected " + this + ", got " + verdict.kind() + (verdict.reason() == null ? "" : " (" + verdict.reason() + ")");
            }
            if (kind == Verdict.Kind.REJECTED && result.uplc() instanceof Observation.Rejected r
                    && !detail.equals(r.code() == null ? NO_CODE : r.code())) {
                return "expected rejection " + detail + ", got " + r.code() + ": " + r.message();
            }
            if (kind == Verdict.Kind.MISMATCH) {
                if (jvm == null || uplc == null) return this + " has no pinned observations";
                String problem = pinMismatch("JVM", jvm, result.jvm());
                if (problem == null) problem = pinMismatch("UPLC", uplc, result.uplc());
                if (problem != null) return "expected " + this + ": " + problem;
            }
            return null;
        }

        private static String pinMismatch(String side, Observation pin, Observation actual) {
            boolean same = switch (pin) {
                case Observation.Value p -> actual instanceof Observation.Value a
                        && p.data().equals(a.data()) && p.traces().equals(a.traces());
                case Observation.Failure p -> actual instanceof Observation.Failure a && p.traces().equals(a.traces());
                case Observation.Inconclusive p -> actual instanceof Observation.Inconclusive a && p.reason().equals(a.reason());
                default -> false;
            };
            return same ? null : side + " should be " + ObservationText.format(pin) + ", got " + ObservationText.format(actual);
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
        Integer format = null;
        Expectation expectation = null;
        Observation jvmPin = null, uplcPin = null;
        var overrides = new EnumMap<OptimizationLevel, Expectation>(OptimizationLevel.class);
        var uplcPins = new EnumMap<OptimizationLevel, Observation>(OptimizationLevel.class);
        var provenance = new LinkedHashMap<String, String>();
        var recorded = new LinkedHashMap<String, String>();
        var args = new ArrayList<PlutusData>();
        var tags = new ArrayList<TagTable.Entry>();
        String profile = null;
        for (String line : text.substring(0, separator).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) throw new HarnessException(file + ": header line without a key: " + line);
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            try {
                switch (key) {
                    case "format" -> format = Integer.parseInt(value);
                    case "name" -> name = value;
                    case "class" -> className = value;
                    case "method" -> method = value;
                    case "note" -> note = note == null ? value : note + " " + value;
                    case "arg" -> args.add(PlutusDataCborDecoder.decode(HexFormat.of().parseHex(value)));
                    case "tag" -> tags.add(TagTable.Entry.parse(value));
                    case "profile" -> {
                        if (profile != null) throw new HarnessException("two profile lines");
                        profile = value;
                    }
                    case "expect" -> expectation = Expectation.parse(value);
                    case "jvm" -> jvmPin = ObservationText.parse(value);
                    case "uplc" -> uplcPin = ObservationText.parse(value);
                    default -> {
                        if (key.startsWith("expect.")) {
                            overrides.put(level(key, "expect."), Expectation.parse(value));
                        } else if (key.startsWith("uplc.")) {
                            uplcPins.put(level(key, "uplc."), ObservationText.parse(value));
                        } else if (key.startsWith("found.")) {
                            provenance.merge(key.substring("found.".length()), value, (a, b) -> a + " " + b);
                        } else if (key.startsWith("recorded.")) {
                            // History is not checked, but it must stay readable.
                            if (!key.endsWith(".cost")) ObservationText.parse(value);
                            recorded.put(key.substring("recorded.".length()), value);
                        } else {
                            throw new HarnessException("unknown header " + key);
                        }
                    }
                }
            } catch (HarnessException | IllegalArgumentException e) {
                throw new HarnessException(file + ": " + e.getMessage(), e);
            }
        }
        if (format == null || format != FORMAT && format != FORMAT_2) {
            throw new HarnessException(file + ": format " + FORMAT_2 + " or " + FORMAT + " is required, found " + format);
        }
        if (format == FORMAT_2 && (!tags.isEmpty() || profile != null)) {
            throw new HarnessException(file + ": tag: and profile: lines need format " + FORMAT);
        }
        if (name == null || className == null || method == null || expectation == null) {
            throw new HarnessException(file + ": name, class, method and expect are required");
        }
        boolean diverges = expectation.kind() == Verdict.Kind.MISMATCH
                || overrides.values().stream().anyMatch(e -> e.kind() == Verdict.Kind.MISMATCH);
        if (!diverges && (jvmPin != null || uplcPin != null || !uplcPins.isEmpty())) {
            throw new HarnessException(file + ": jvm: and uplc: pin a DIVERGE expectation, and there is none");
        }
        try {
            expectation = pin(expectation, jvmPin, uplcPin);
            for (var level : OptimizationLevel.values()) {
                if (overrides.containsKey(level) || uplcPins.containsKey(level)) {
                    overrides.put(level, pin(overrides.getOrDefault(level, expectation), jvmPin,
                            uplcPins.getOrDefault(level, uplcPin)));
                }
            }
        } catch (HarnessException e) {
            throw new HarnessException(file + ": " + e.getMessage(), e);
        }
        DiffCase diffCase;
        try {
            diffCase = new DiffCase(name, text.substring(separator + 5), className, method, args, new TagTable(tags), profile);
        } catch (HarnessException e) {
            throw new HarnessException(file + ": " + e.getMessage(), e);
        }
        return new SavedCase(file, diffCase, expectation, Collections.unmodifiableMap(overrides), note,
                Collections.unmodifiableMap(provenance), Collections.unmodifiableMap(recorded));
    }

    private static OptimizationLevel level(String key, String prefix) {
        return OptimizationLevel.valueOf(key.substring(prefix.length()));
    }

    private static Expectation pin(Expectation expectation, Observation jvm, Observation uplc) {
        if (expectation.kind() != Verdict.Kind.MISMATCH) return expectation;
        return expectation.pinned(jvm, uplc);
    }

    /**
     * Writes a case so that {@link #read} returns it unchanged; used to save shrunk failures. The
     * provenance keys are written as {@code found.<key>}; the report, if any, as {@code recorded.} history.
     */
    public static void write(Path file, DiffCase diffCase, Expectation expectation, String note,
                             Map<String, String> provenance, DiffRunner.Report report) {
        var text = new StringBuilder();
        boolean format2 = diffCase.tags().isEmpty() && diffCase.profile() == null;
        text.append("format: ").append(format2 ? FORMAT_2 : FORMAT).append('\n');
        text.append("name: ").append(diffCase.name()).append('\n');
        text.append("class: ").append(diffCase.className()).append('\n');
        text.append("method: ").append(diffCase.method()).append('\n');
        for (String tag : diffCase.tags().lines()) text.append("tag: ").append(tag).append('\n');
        if (diffCase.profile() != null) text.append("profile: ").append(diffCase.profile()).append('\n');
        for (PlutusData arg : diffCase.args()) {
            text.append("arg: ").append(HexFormat.of().formatHex(PlutusDataCborEncoder.encode(arg))).append('\n');
        }
        text.append("expect: ").append(expectation).append('\n');
        if (expectation.kind() == Verdict.Kind.MISMATCH) {
            text.append("jvm: ").append(ObservationText.format(expectation.jvm())).append('\n');
            text.append("uplc: ").append(ObservationText.format(expectation.uplc())).append('\n');
        }
        if (note != null) text.append("note: ").append(note).append('\n');
        provenance.forEach((key, value) -> text.append("found.").append(key).append(": ").append(value).append('\n'));
        if (report != null) {
            text.append("recorded.jvm: ").append(ObservationText.format(report.jvm())).append('\n');
            report.results().forEach((config, result) -> {
                text.append("recorded.").append(config).append(": ").append(ObservationText.format(result.uplc())).append('\n');
                if (result.measurement() != null) {
                    text.append("recorded.").append(config).append(".cost: ").append(result.measurement()).append('\n');
                }
            });
        }
        text.append("---\n").append(diffCase.source());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Adds one provenance line to a saved file, before its source; for facts known only later, such as the seed. */
    public static void addProvenance(Path file, String key, String value) {
        addHeader(file, "found." + key, value);
    }

    /**
     * Adds one {@code recorded.} history line to a saved file, before its source, such as the outcome of a
     * confirmation rerun (ADR-063 D6, r6). A key that does not end in {@code .cost} must hold an observation.
     */
    public static void addRecorded(Path file, String key, String value) {
        if (!key.endsWith(".cost")) ObservationText.parse(value);
        addHeader(file, "recorded." + key, value);
    }

    private static void addHeader(Path file, String key, String value) {
        try {
            String text = Files.readString(file);
            int separator = text.indexOf("\n---\n");
            if (separator < 0) throw new HarnessException(file + ": no --- line between header and source");
            Files.writeString(file, text.substring(0, separator) + "\n" + key + ": " + value + text.substring(separator));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
